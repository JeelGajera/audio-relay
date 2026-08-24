use std::process::Command;

/// Exposes build-time git metadata (commit hash + commit date) as
/// compile-time env vars, consumed via `env!(...)` in `src/ui/mod.rs`'s
/// About tab. Falls back to "unknown" rather than failing the build if git
/// isn't available (e.g. building from a source tarball without a .git dir).
fn main() {
    println!("cargo:rerun-if-changed=../.git/HEAD");
    println!(
        "cargo:rustc-env=GIT_HASH={}",
        git_output(&["rev-parse", "--short=10", "HEAD"])
    );
    println!(
        "cargo:rustc-env=GIT_COMMIT_DATE={}",
        git_output(&["show", "-s", "--format=%cs", "HEAD"])
    );
}

fn git_output(args: &[&str]) -> String {
    Command::new("git")
        .args(args)
        .output()
        .ok()
        .filter(|o| o.status.success())
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "unknown".to_string())
}
