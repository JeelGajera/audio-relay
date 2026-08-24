//! TCP control channel: accepts phone connections, runs the pairing
//! handshake, then the heartbeat loop. One task per connected phone. See
//! `protocol-spec.md` §4 for the message sequence this implements
//! step-for-step.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::time::interval;
use tracing::{info, warn};

use crate::protocol::control::{
    Capabilities, ControlMessage, HelloAck, PairFail, PairOk, Ping, Pong,
};
use crate::protocol::crypto;
use crate::protocol::PROTOCOL_VERSION;
use crate::state::{ActiveSession, AppState, ConnectionStatus};

const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(1);
const MISSED_BEATS_BEFORE_DISCONNECT: u32 = 3;
const MAX_PAIR_ATTEMPTS: u32 = 5;

pub async fn run(state: Arc<AppState>, port: u16) -> std::io::Result<()> {
    let listener = TcpListener::bind(("0.0.0.0", port)).await?;
    info!("control channel listening on port {port}");
    loop {
        let (socket, peer_addr) = listener.accept().await?;
        let state = state.clone();
        tokio::spawn(async move {
            if let Err(e) = handle_connection(state, socket, peer_addr).await {
                warn!(%peer_addr, error = %e, "control connection ended with an error");
            }
        });
    }
}

#[derive(Debug, thiserror::Error)]
enum ConnError {
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("protocol error: {0}")]
    Protocol(#[from] crate::protocol::control::ControlError),
    #[error("connection closed before completing the handshake")]
    ClosedEarly,
    #[error("expected HELLO as the first message, got something else")]
    ExpectedHello,
    #[error("pairing did not complete after {MAX_PAIR_ATTEMPTS} attempts")]
    PairingGaveUp,
}

async fn handle_connection(
    state: Arc<AppState>,
    socket: TcpStream,
    peer_addr: SocketAddr,
) -> Result<(), ConnError> {
    socket.set_nodelay(true).ok();
    let (read_half, mut write_half) = socket.into_split();
    let mut reader = BufReader::new(read_half);
    let mut line = String::new();

    // --- HELLO ---
    let hello = loop {
        line.clear();
        if reader.read_line(&mut line).await? == 0 {
            return Err(ConnError::ClosedEarly);
        }
        match ControlMessage::from_line(&line)? {
            Some(ControlMessage::Hello(h)) => break h,
            Some(_) => return Err(ConnError::ExpectedHello),
            None => continue, // unknown message type before HELLO; ignore and keep waiting
        }
    };
    info!(device = %hello.device_name, %peer_addr, "phone connected");

    let (device_id, laptop_device_id) = {
        let config = state.config.lock().unwrap();
        (hello.device_id.clone(), config.device_id.clone())
    };

    let already_paired = {
        let config = state.config.lock().unwrap();
        config.paired_devices.contains_key(&device_id)
    };

    let repair_nonce = crypto::hex_encode(crypto::generate_session_id());
    let ack = ControlMessage::HelloAck(HelloAck {
        protocol_version: PROTOCOL_VERSION,
        device_id: laptop_device_id.clone(),
        device_name: hostname(),
        paired: already_paired,
        nonce: already_paired.then_some(repair_nonce.clone()),
    });
    write_half.write_all(ack.to_line()?.as_bytes()).await?;

    // --- Pairing / reconnect ---
    let session_key = complete_pairing(
        &state,
        &mut reader,
        &mut write_half,
        &device_id,
        &laptop_device_id,
        &hello.device_name,
        &repair_nonce,
    )
    .await?;

    let session_id = crypto::generate_session_id();
    write_half
        .write_all(
            ControlMessage::PairOk(PairOk {
                session_id: crypto::hex_encode(session_id),
                session_key: None, // already sent inline during complete_pairing on first pair
            })
            .to_line()?
            .as_bytes(),
        )
        .await?;

    // --- Capability exchange ---
    write_half
        .write_all(
            ControlMessage::Capabilities(Capabilities {
                sample_rate: 48_000,
                channels: 2,
            })
            .to_line()?
            .as_bytes(),
        )
        .await?;
    line.clear();
    reader.read_line(&mut line).await?;
    if let Some(ControlMessage::Capabilities(caps)) = ControlMessage::from_line(&line)? {
        info!(
            sample_rate = caps.sample_rate,
            channels = caps.channels,
            "phone capabilities"
        );
    }

    let audio_addr = SocketAddr::new(peer_addr.ip(), hello.audio_port);
    *state.session.lock().unwrap() = Some(ActiveSession {
        phone_device_id: device_id.clone(),
        phone_device_name: hello.device_name.clone(),
        audio_addr,
        session_key,
        session_id,
    });
    state.set_status(ConnectionStatus::Streaming {
        device_name: hello.device_name.clone(),
    });
    info!(%audio_addr, "session established, audio streaming can begin");

    let result = heartbeat_loop(&mut reader, &mut write_half).await;

    let device_name = state
        .clear_session_if(&device_id)
        .unwrap_or_else(|| hello.device_name.clone());
    state.set_status(ConnectionStatus::Disconnected {
        device_name: device_name.clone(),
    });
    info!(device = %device_name, "phone disconnected");
    result
}

#[allow(clippy::too_many_arguments)]
async fn complete_pairing(
    state: &Arc<AppState>,
    reader: &mut BufReader<tokio::net::tcp::OwnedReadHalf>,
    write_half: &mut tokio::net::tcp::OwnedWriteHalf,
    device_id: &str,
    laptop_device_id: &str,
    device_name: &str,
    repair_nonce: &str,
) -> Result<crypto::SessionKey, ConnError> {
    let mut line = String::new();
    for _ in 0..MAX_PAIR_ATTEMPTS {
        line.clear();
        if reader.read_line(&mut line).await? == 0 {
            return Err(ConnError::ClosedEarly);
        }
        match ControlMessage::from_line(&line)? {
            Some(ControlMessage::PairRequest(req)) => {
                if state.check_pairing_code(&req.code) {
                    let key = crypto::derive_session_key(&req.code, device_id, laptop_device_id);
                    state.config.lock().unwrap().remember_device(
                        device_id,
                        device_name,
                        &crypto::hex_encode(key),
                    );
                    let _ = state.config.lock().unwrap().save();
                    return Ok(key);
                }
                write_half
                    .write_all(
                        ControlMessage::PairFail(PairFail {
                            reason: "invalid or expired pairing code".into(),
                        })
                        .to_line()?
                        .as_bytes(),
                    )
                    .await?;
            }
            Some(ControlMessage::Repair(rep)) if rep.device_id == device_id => {
                let persisted_key_hex = state
                    .config
                    .lock()
                    .unwrap()
                    .paired_devices
                    .get(device_id)
                    .map(|d| d.session_key_hex.clone());

                let verified = persisted_key_hex.as_deref().and_then(|hex| {
                    let bytes = crypto::hex_decode(hex).ok()?;
                    let key: crypto::SessionKey = bytes.try_into().ok()?;
                    crypto::verify_repair_proof(&key, device_id, repair_nonce, &rep.proof)
                        .then_some(key)
                });

                match verified {
                    Some(key) => return Ok(key),
                    None => {
                        write_half
                            .write_all(
                                ControlMessage::PairFail(PairFail {
                                    reason: "reconnect proof did not verify".into(),
                                })
                                .to_line()?
                                .as_bytes(),
                            )
                            .await?;
                    }
                }
            }
            _ => continue, // ignore anything else while waiting to pair
        }
    }
    Err(ConnError::PairingGaveUp)
}

async fn heartbeat_loop(
    reader: &mut BufReader<tokio::net::tcp::OwnedReadHalf>,
    write_half: &mut tokio::net::tcp::OwnedWriteHalf,
) -> Result<(), ConnError> {
    let mut ticker = interval(HEARTBEAT_INTERVAL);
    let mut last_pong = Instant::now();
    let mut line = String::new();

    loop {
        tokio::select! {
            _ = ticker.tick() => {
                if last_pong.elapsed() > HEARTBEAT_INTERVAL * MISSED_BEATS_BEFORE_DISCONNECT {
                    return Ok(()); // treated as a clean disconnect, not an error
                }
                let ping = ControlMessage::Ping(Ping { t: now_ms() });
                write_half.write_all(ping.to_line()?.as_bytes()).await?;
            }
            n = { line.clear(); reader.read_line(&mut line) } => {
                if n? == 0 {
                    return Ok(());
                }
                match ControlMessage::from_line(&line)? {
                    Some(ControlMessage::Ping(p)) => {
                        let pong = ControlMessage::Pong(Pong { t: p.t });
                        write_half.write_all(pong.to_line()?.as_bytes()).await?;
                    }
                    Some(ControlMessage::Pong(_)) => {
                        last_pong = Instant::now();
                    }
                    Some(ControlMessage::Bye) => {
                        return Ok(());
                    }
                    _ => {} // ignore anything else on the heartbeat loop
                }
            }
        }
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn hostname() -> String {
    hostname::get()
        .ok()
        .and_then(|h| h.into_string().ok())
        .unwrap_or_else(|| "audio-relay-laptop".to_string())
}
