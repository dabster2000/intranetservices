-- Create user_activity table
CREATE TABLE user_activity (
                               id BIGINT AUTO_INCREMENT PRIMARY KEY,
                               user_uuid VARCHAR(36) NOT NULL,
                               route VARCHAR(255) NOT NULL,
                               timestamp DATETIME NOT NULL,
                               duration_seconds BIGINT NOT NULL,
                               INDEX idx_user_uuid (user_uuid),
                               INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Create index for querying most visited routes
CREATE INDEX idx_route_timestamp ON user_activity (route, timestamp);

-- Create user_session table
CREATE TABLE user_session (
                              id BIGINT AUTO_INCREMENT PRIMARY KEY,
                              session_id VARCHAR(255) NOT NULL,
                              user_uuid VARCHAR(36) NOT NULL,
                              login_time DATETIME NOT NULL,
                              last_activity DATETIME NOT NULL,
                              logout_time DATETIME,
                              INDEX idx_user_uuid (user_uuid),
                              INDEX idx_login_time (login_time),
                              INDEX idx_last_activity (last_activity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

