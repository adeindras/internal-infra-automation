use axum::{ extract::State, http::StatusCode, response::IntoResponse, Json };
use crate::{ model::{ AppState } };

use prometheus::{ Encoder, TextEncoder };

pub async fn health_checker_handler(State(app_state): State<AppState>) -> Result<
    impl IntoResponse,
    (StatusCode, Json<serde_json::Value>)
> {
    let message: &str = &app_state.config.env;

    let json_response = serde_json::json!({
      "status": "success",
      "message": message
  });

    return Ok((StatusCode::OK, Json(json_response)));
}

pub async fn metrics_handler(State(app_state): State<AppState>) -> String {
    let mut buffer = vec![];
    let encoder = TextEncoder::new();
    app_state.metric.ccu_template.set(app_state.config.ccu);
    let mf = prometheus::gather();
    encoder.encode(&mf, &mut buffer).unwrap();
    String::from_utf8(buffer).unwrap_or_default()
}
