use crate::model::Snapshot;
use std::io::Read;
use std::time::Duration;

#[derive(Clone, Debug)]
pub struct WebDavConfig {
    pub url: String,
    pub username: String,
    pub password: String,
}

pub enum PullResult {
    NotModified,
    Snapshot { snapshot: Snapshot, etag: Option<String> },
    Missing,
}

pub struct WebDav {
    agent: ureq::Agent,
    config: WebDavConfig,
}

impl WebDav {
    pub fn new(config: WebDavConfig) -> Self {
        let agent = ureq::AgentBuilder::new()
            .timeout_connect(Duration::from_secs(6))
            .timeout_read(Duration::from_secs(10))
            .timeout_write(Duration::from_secs(10))
            .build();
        Self { agent, config }
    }

    pub fn pull(&self, etag: Option<&str>) -> Result<PullResult, String> {
        let mut request = self.agent.get(&self.config.url)
            .set("Accept", "application/json")
            .set("User-Agent", "ChaChaCalendar/CloudSync");
        if !self.config.username.is_empty() {
            request = request.auth(&self.config.username, &self.config.password);
        }
        if let Some(value) = etag {
            request = request.set("If-None-Match", value);
        }

        match request.call() {
            Ok(response) => {
                let response_etag = response.header("ETag").map(str::to_owned);
                let mut body = String::new();
                response.into_reader().read_to_string(&mut body).map_err(|e| e.to_string())?;
                let snapshot = serde_json::from_str::<Snapshot>(&body).map_err(|e| e.to_string())?;
                Ok(PullResult::Snapshot { snapshot, etag: response_etag })
            }
            Err(ureq::Error::Status(304, _)) => Ok(PullResult::NotModified),
            Err(ureq::Error::Status(404, _)) => Ok(PullResult::Missing),
            Err(error) => Err(error.to_string()),
        }
    }

    pub fn push(&self, snapshot: &Snapshot, etag: Option<&str>) -> Result<Option<String>, String> {
        let body = serde_json::to_string(snapshot).map_err(|e| e.to_string())?;
        let mut request = self.agent.put(&self.config.url)
            .set("Content-Type", "application/json; charset=utf-8")
            .set("User-Agent", "ChaChaCalendar/CloudSync");
        if !self.config.username.is_empty() {
            request = request.auth(&self.config.username, &self.config.password);
        }
        if let Some(value) = etag {
            request = request.set("If-Match", value);
        } else {
            request = request.set("If-None-Match", "*");
        }

        match request.send_string(&body) {
            Ok(response) => Ok(response.header("ETag").map(str::to_owned)),
            Err(ureq::Error::Status(412, _)) => Err("precondition_failed".into()),
            Err(error) => Err(error.to_string()),
        }
    }
}
