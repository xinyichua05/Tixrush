-- "user" is a reserved word in PostgreSQL, so the table is called "users".
CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(254) NOT NULL UNIQUE,   -- UNIQUE also creates an index for fast login lookups
    password_hash VARCHAR(100) NOT NULL,          -- BCrypt hash, never the plain password
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
