CREATE TABLE IF NOT EXISTS system_config (
    config_key VARCHAR(255) PRIMARY KEY,
    config_value TEXT
);

INSERT INTO system_config (config_key, config_value) VALUES 
('provider', 'ollama'),
('base_url', 'http://localhost:11434'),
('api_key', ''),
('active_chat_model', 'qwen2.5-coder:latest'),
('active_embedding_model', 'nomic-embed-text')
ON CONFLICT (config_key) DO NOTHING;
