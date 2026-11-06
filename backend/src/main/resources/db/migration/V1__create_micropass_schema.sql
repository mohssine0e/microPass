CREATE TABLE apps (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    git_url VARCHAR(2048) NOT NULL,
    default_branch VARCHAR(255) NOT NULL,
    dockerfile_path VARCHAR(500) NOT NULL,
    target_port INTEGER NOT NULL,
    allocated_port INTEGER,
    active_container_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE deployments (
    id BIGSERIAL PRIMARY KEY,
    app_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    commit_hash VARCHAR(255),
    image_tag VARCHAR(255),
    container_id VARCHAR(255),
    port INTEGER,
    build_logs TEXT,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    CONSTRAINT fk_deployments_app
        FOREIGN KEY (app_id) REFERENCES apps(id) ON DELETE CASCADE
);

CREATE INDEX idx_deployments_app_id_id ON deployments(app_id, id DESC);
CREATE INDEX idx_deployments_status ON deployments(status);
