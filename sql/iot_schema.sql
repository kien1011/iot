-- Chạy một lần trên schema mới. Không chạy lại để reset dữ liệu trước mỗi demo.
CREATE DATABASE IF NOT EXISTS iot_control_center CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE iot_control_center;
SET SESSION time_zone = '+07:00';

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(150) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB;

CREATE TABLE sensors (
    id INT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE devices (
    id INT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    status ENUM('ON','OFF') NOT NULL DEFAULT 'OFF',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE sensor_data (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sensor_id INT NOT NULL,
    value DOUBLE NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY ix_sensor_time (sensor_id, created_at, id),
    KEY ix_sensor_value (sensor_id, value, id),
    KEY ix_sensor_created (created_at, id),
    CONSTRAINT fk_data_sensor FOREIGN KEY (sensor_id) REFERENCES sensors(id)
) ENGINE=InnoDB;

CREATE TABLE action_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    device_id INT NOT NULL,
    action ENUM('ON','OFF') NOT NULL,
    status ENUM('LOADING','SUCCESS','FAILED') NOT NULL DEFAULT 'LOADING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY ix_action_device_time (device_id, created_at, id),
    KEY ix_action_user (user_id),
    KEY ix_action_created (created_at, id),
    CONSTRAINT fk_action_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_action_device FOREIGN KEY (device_id) REFERENCES devices(id)
) ENGINE=InnoDB;

-- BCrypt của 123456; được kiểm tra bằng BCryptPasswordEncoder trong bộ test.
INSERT INTO users (id, username, password, full_name)
VALUES (1, 'admin', '$2b$12$TvmWX9gKPRD3ySaY6knhJua0lcsywTkWSZGe63G3LrnpMLT/f0SAO', 'Administrator');
INSERT INTO sensors (id, name) VALUES (1, 'Temperature'), (2, 'Humidity'), (3, 'Light');
INSERT INTO devices (id, name, status) VALUES (1, 'Light 1', 'OFF'), (2, 'Light 2', 'OFF');
-- sensor_data và action_history cố ý trống. updated_at do MySQL khởi tạo.
