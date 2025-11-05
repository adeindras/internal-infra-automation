mod handler;
mod router;
mod metrics;
pub mod model;

use tokio::signal;
use axum;
use structopt::StructOpt;

use crate::{ router::{ create_router }, model::{ Config, AppState }, metrics::{ metrics_init } };

#[tokio::main]
async fn main() {
    let config = Config::from_args();
    let metric = metrics_init(config.clone());
    let app_state = AppState { config: config.clone(), metric };
    let app = create_router(app_state);

    println!("🚀 Server started");
    let listener = tokio::net::TcpListener::bind("0.0.0.0:8888").await.unwrap();

    println!("🎧 Listening on {}", listener.local_addr().unwrap());
    println!("🛰️  With {:?}", config);
    axum::serve(listener, app).with_graceful_shutdown(shutdown_signal()).await.unwrap();
}

async fn shutdown_signal() {
    let ctrl_c = async {
        signal::ctrl_c().await.expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        signal::unix
            ::signal(signal::unix::SignalKind::terminate())
            .expect("failed to install signal handler")
            .recv().await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
