-- A library, with every shape the SQL wizard renders differently (WizardGoldenOutputTests).
CREATE TABLE authors (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    email VARCHAR(200) UNIQUE,
    born DATE,
    rating NUMERIC(4,2),
    active BOOLEAN NOT NULL,
    bio TEXT,
    external_ref UUID,
    profile JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- A key the client supplies.
CREATE TABLE publishers (
    code VARCHAR(10) PRIMARY KEY,
    name VARCHAR(120) NOT NULL
);

-- Foreign keys: inline, two to one table, to a table outside the script, and to itself.
CREATE TABLE books (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    author_id BIGINT NOT NULL REFERENCES authors(id),
    editor_id BIGINT,
    publisher_code VARCHAR(10),
    warehouse_id BIGINT,
    parent_id BIGINT,
    price NUMERIC(10,2),
    pages INTEGER,
    published_on DATE,
    FOREIGN KEY (editor_id) REFERENCES authors(id),
    FOREIGN KEY (publisher_code) REFERENCES publishers(code),
    FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
    FOREIGN KEY (parent_id) REFERENCES books(id)
);

-- A composite key, one part of it a foreign key.
CREATE TABLE book_copies (
    book_id BIGINT NOT NULL,
    copy_no INTEGER NOT NULL,
    shelf VARCHAR(20),
    PRIMARY KEY (book_id, copy_no),
    FOREIGN KEY (book_id) REFERENCES books(id)
);

-- A composite foreign key.
CREATE TABLE loans (
    id BIGSERIAL PRIMARY KEY,
    book_id BIGINT NOT NULL,
    copy_no INTEGER NOT NULL,
    due_on DATE NOT NULL,
    returned BOOLEAN,
    FOREIGN KEY (book_id, copy_no) REFERENCES book_copies(book_id, copy_no)
);

-- No primary key: entity only, no REST stack.
CREATE TABLE audit_log (
    happened_at TIMESTAMP NOT NULL,
    message VARCHAR(500)
);

-- Quoted, mixed-case names: the naming strategy class.
CREATE TABLE "OrderItems" (
    "Id" BIGSERIAL PRIMARY KEY,
    "unitPrice" NUMERIC(10,2),
    "orderedOn" DATE
);

COMMENT ON TABLE authors IS 'statements the wizard skips';
CREATE INDEX idx_books_title ON books(title);

-- A SELECT becomes a read-only view entity.
SELECT a.name AS author_name, count(*) AS book_count
FROM authors a JOIN books b ON b.author_id = a.id
GROUP BY a.name;
