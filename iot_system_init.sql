CREATE DATABASE IF NOT EXISTS iot_system
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE iot_system;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(150) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sensors (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS devices (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    status ENUM('ON', 'OFF') NOT NULL DEFAULT 'OFF',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sensor_data (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sensor_id INT NOT NULL,
    value DOUBLE NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sensor_data_sensor
        FOREIGN KEY (sensor_id) REFERENCES sensors(id),
    INDEX idx_sensor_data_sensor_time (sensor_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS action_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    device_id INT NOT NULL,
    action ENUM('ON', 'OFF') NOT NULL,
    status ENUM('ON', 'OFF', 'LOADING') NOT NULL DEFAULT 'LOADING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_action_history_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_action_history_device
        FOREIGN KEY (device_id) REFERENCES devices(id),
    INDEX idx_action_history_device_time (device_id, created_at),
    INDEX idx_action_history_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO users (id, username, password, full_name, created_at) VALUES
    (1, 'admin',  '$2y$10$JxmC5GHyAv/i1JWGGG6c/.BjB4EiNAWv7pVkmW2K1LLJ6NI1DPXXe', 'Administrator', CURRENT_TIMESTAMP),
    (2, 'user01', '$2y$10$3IKHABFOohFYbWrJt6h9I.pY88fOjXFWc2bIxFxsFJtNktSwiqjfq', 'User 01', CURRENT_TIMESTAMP),
    (3, 'user02', '$2y$10$apCy47265Koc0Ku0KD4zAOx.Q2q19NP7vh.7clbgO4nF6WGvpHuZS', 'User 02', CURRENT_TIMESTAMP);

INSERT IGNORE INTO sensors (id, name, created_at) VALUES
    (1, 'Temperature', CURRENT_TIMESTAMP),
    (2, 'Humidity', CURRENT_TIMESTAMP),
    (3, 'Light', CURRENT_TIMESTAMP);

INSERT IGNORE INTO devices (id, name, status, created_at) VALUES
    (1, 'Light 1', 'OFF', CURRENT_TIMESTAMP),
    (2, 'Light 2', 'OFF', CURRENT_TIMESTAMP);
