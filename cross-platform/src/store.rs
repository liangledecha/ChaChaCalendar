use crate::model::{EventRecord, Snapshot};
use rusqlite::{params, Connection, OptionalExtension};
use std::path::{Path, PathBuf};

pub struct Store {
    conn: Connection,
}

impl Store {
    pub fn open_default() -> rusqlite::Result<Self> {
        let path = default_database_path();
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        Self::open(path)
    }

    pub fn open(path: impl AsRef<Path>) -> rusqlite::Result<Self> {
        let conn = Connection::open(path)?;
        conn.execute_batch(
            "PRAGMA journal_mode=WAL;
             PRAGMA synchronous=NORMAL;
             CREATE TABLE IF NOT EXISTS sync_events(
               id TEXT PRIMARY KEY NOT NULL,
               clock_counter INTEGER NOT NULL,
               clock_device TEXT NOT NULL,
               deleted INTEGER NOT NULL DEFAULT 0,
               json TEXT NOT NULL
             );
             CREATE TABLE IF NOT EXISTS sync_meta(
               key TEXT PRIMARY KEY NOT NULL,
               value TEXT NOT NULL
             );"
        )?;
        Ok(Self { conn })
    }

    pub fn load_snapshot(&self, device_id: &str) -> rusqlite::Result<Snapshot> {
        let mut statement = self.conn.prepare(
            "SELECT json FROM sync_events ORDER BY id"
        )?;
        let rows = statement.query_map([], |row| row.get::<_, String>(0))?;
        let mut events = Vec::new();
        for row in rows {
            let json = row?;
            if let Ok(item) = serde_json::from_str::<EventRecord>(&json) {
                events.push(item);
            }
        }
        Ok(Snapshot { schema: 1, updated_by: device_id.to_owned(), events })
    }

    pub fn replace_snapshot(&mut self, snapshot: &Snapshot) -> rusqlite::Result<()> {
        let tx = self.conn.transaction()?;
        {
            let mut upsert = tx.prepare(
                "INSERT INTO sync_events(id,clock_counter,clock_device,deleted,json)
                 VALUES(?1,?2,?3,?4,?5)
                 ON CONFLICT(id) DO UPDATE SET
                   clock_counter=excluded.clock_counter,
                   clock_device=excluded.clock_device,
                   deleted=excluded.deleted,
                   json=excluded.json"
            )?;
            for event in &snapshot.events {
                let json = serde_json::to_string(event)
                    .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?;
                upsert.execute(params![
                    event.id,
                    event.clock.counter as i64,
                    event.clock.device,
                    if event.deleted { 1 } else { 0 },
                    json
                ])?;
            }
        }
        tx.commit()
    }

    pub fn meta(&self, key: &str) -> rusqlite::Result<Option<String>> {
        self.conn.query_row(
            "SELECT value FROM sync_meta WHERE key=?1",
            [key],
            |row| row.get(0),
        ).optional()
    }

    pub fn set_meta(&self, key: &str, value: &str) -> rusqlite::Result<()> {
        self.conn.execute(
            "INSERT INTO sync_meta(key,value) VALUES(?1,?2)
             ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            params![key, value],
        )?;
        Ok(())
    }

    pub fn device_id(&self) -> rusqlite::Result<String> {
        if let Some(id) = self.meta("device_id")? {
            return Ok(id);
        }
        let id = uuid::Uuid::new_v4().to_string();
        self.set_meta("device_id", &id)?;
        Ok(id)
    }
}

fn default_database_path() -> PathBuf {
    #[cfg(target_os = "windows")]
    {
        let base = std::env::var_os("LOCALAPPDATA")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("."));
        return base.join("ChaChaCalendar").join("chacha-calendar.db");
    }
    #[cfg(target_os = "ios")]
    {
        let base = std::env::var_os("HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("."));
        return base.join("Documents").join("chacha-calendar.db");
    }
    #[cfg(not(any(target_os = "windows", target_os = "ios")))]
    {
        let base = std::env::var_os("XDG_DATA_HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|v| PathBuf::from(v).join(".local/share")))
            .unwrap_or_else(|| PathBuf::from("."));
        base.join("chacha-calendar").join("chacha-calendar.db")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn persists_device_id() {
        let store = Store::open(":memory:").unwrap();
        let first = store.device_id().unwrap();
        let second = store.device_id().unwrap();
        assert_eq!(first, second);
    }
}
