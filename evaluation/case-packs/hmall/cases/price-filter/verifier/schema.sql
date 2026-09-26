CREATE TABLE item (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255), price INTEGER, stock INTEGER, image VARCHAR(255),
    category VARCHAR(100), brand VARCHAR(100), spec VARCHAR(255), sold INTEGER,
    comment_count INTEGER, isAD BOOLEAN, status INTEGER,
    create_time TIMESTAMP, update_time TIMESTAMP, creater BIGINT, updater BIGINT
);
