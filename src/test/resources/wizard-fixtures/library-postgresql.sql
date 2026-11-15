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

-- A key that is itself a foreign key: a shared-key one-to-one.
CREATE TABLE author_profiles (
    author_id BIGINT PRIMARY KEY REFERENCES authors(id),
    nickname VARCHAR(80),
    website VARCHAR(300)
);

-- A composite foreign key to a table outside the script: scalars with a TODO.
CREATE TABLE shipments (
    id BIGSERIAL PRIMARY KEY,
    depot_id BIGINT NOT NULL,
    bay_no INTEGER NOT NULL,
    weight NUMERIC(8,3),
    FOREIGN KEY (depot_id, bay_no) REFERENCES depots(depot_id, bay_no)
);

-- A schema-qualified table.
CREATE TABLE inv.stock_levels (
    sku VARCHAR(40) PRIMARY KEY,
    on_hand INTEGER NOT NULL,
    counted_at TIMESTAMP
);

-- A column a CHECK limits to a set of values is an enum; one whose values are not Java
-- identifiers stays text. And a UNIQUE over two columns.
CREATE TABLE memberships (
    id BIGSERIAL PRIMARY KEY,
    author_id BIGINT NOT NULL,
    tier VARCHAR(20) NOT NULL CHECK (tier IN ('BASIC', 'GOLD', 'PLATINUM')),
    channel VARCHAR(20) CHECK (channel IN ('e-mail', 'post')),
    starts_on DATE NOT NULL,
    UNIQUE (author_id, starts_on)
);

-- Constraints declared after the table, the way pg_dump writes them.
CREATE TABLE reviews (
    id BIGINT NOT NULL,
    book_id BIGINT NOT NULL,
    reviewer VARCHAR(120) NOT NULL,
    stars INTEGER
);
ALTER TABLE ONLY reviews ADD CONSTRAINT reviews_pkey PRIMARY KEY (id);
ALTER TABLE ONLY reviews ADD CONSTRAINT reviews_book_fk FOREIGN KEY (book_id) REFERENCES books(id);
ALTER TABLE reviews ADD CONSTRAINT reviews_once UNIQUE (book_id, reviewer);
ALTER TABLE memberships ADD CONSTRAINT memberships_author_fk FOREIGN KEY (author_id) REFERENCES public.authors (id);
ALTER TABLE authors ADD COLUMN an_alter_the_wizard_skips VARCHAR(10);

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
