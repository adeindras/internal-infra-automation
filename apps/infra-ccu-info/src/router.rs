use axum::{ routing::{ get }, Router };

use crate::{ handler::{ health_checker_handler, metrics_handler }, model::{ AppState } };

pub fn create_router(app_state: AppState) -> Router {
    Router::new()
        .route("/healthchecker", get(health_checker_handler))
        .route("/metrics", get(metrics_handler))
        .with_state(app_state)
}
