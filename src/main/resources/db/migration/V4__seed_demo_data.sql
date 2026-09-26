-- =====================================================================================
-- V4 — Demo seed. The API is explorable the second the app boots, and the oversell
-- demo has a SKU with exactly one unit in stock.
--
-- Every seeded user has the password: Password@123
-- (BCrypt cost 10; the same hash is reused so the demo credentials are memorable.)
-- =====================================================================================

-- ------------------------------------------------------------------ roles & users
INSERT INTO roles (id, name, created_at, updated_at) VALUES
 (1, 'ROLE_ADMIN',           CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (2, 'ROLE_CUSTOMER',        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (3, 'ROLE_WAREHOUSE_STAFF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO users (id, email, password_hash, full_name, phone, zone, enabled, created_at, updated_at) VALUES
 (1, 'admin@oms.dev',    '$2y$10$MOgXXK6Hcr8p0snTG2vmPuOB5jo3/VrF4ixR.pZJ0VqEdhcSB3Fhm', 'Ada Admin',        '9000000001', 'WEST',  TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (2, 'customer@oms.dev', '$2y$10$MOgXXK6Hcr8p0snTG2vmPuOB5jo3/VrF4ixR.pZJ0VqEdhcSB3Fhm', 'Cara Customer',    '9000000002', 'WEST',  TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (3, 'staff.mum@oms.dev','$2y$10$MOgXXK6Hcr8p0snTG2vmPuOB5jo3/VrF4ixR.pZJ0VqEdhcSB3Fhm', 'Sam Staff (Mumbai)','9000000003','WEST',  TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (4, 'staff.del@oms.dev','$2y$10$MOgXXK6Hcr8p0snTG2vmPuOB5jo3/VrF4ixR.pZJ0VqEdhcSB3Fhm', 'Dev Staff (Delhi)', '9000000004','NORTH', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (5, 'customer2@oms.dev','$2y$10$MOgXXK6Hcr8p0snTG2vmPuOB5jo3/VrF4ixR.pZJ0VqEdhcSB3Fhm', 'Bob Buyer',        '9000000005', 'NORTH', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO user_roles (user_id, role_id) VALUES (1, 1), (2, 2), (3, 3), (4, 3), (5, 2);

-- ------------------------------------------------------------------ warehouses
INSERT INTO warehouses (id, code, name, zone, city, active, created_at, updated_at) VALUES
 (1, 'WH-MUM', 'Mumbai Fulfillment Center',    'WEST',  'Mumbai',    TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (2, 'WH-DEL', 'Delhi NCR Fulfillment Center', 'NORTH', 'Gurugram',  TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (3, 'WH-BLR', 'Bengaluru Fulfillment Center', 'SOUTH', 'Bengaluru', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO warehouse_staff (user_id, warehouse_id) VALUES (3, 1), (4, 2);

-- ------------------------------------------------------------------ categories (nested)
INSERT INTO categories (id, name, slug, parent_id, created_at, updated_at) VALUES
 (1, 'Electronics', 'electronics', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (2, 'Mobiles',     'mobiles',     1,    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (3, 'Audio',       'audio',       1,    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (4, 'Apparel',     'apparel',     NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (5, 'Men',         'men',         4,    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (6, 'Women',       'women',       4,    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (7, 'Home',        'home',        NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Deliberately sparse: Mobiles/Audio/Men/Women have NO row of their own, so tax
-- resolution has to walk up to Electronics/Apparel. That upward walk is unit-tested.
INSERT INTO tax_rates (category_id, rate, description, created_at, updated_at) VALUES
 (1, 0.180000, 'GST 18% — Electronics', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (4, 0.050000, 'GST 5% — Apparel',      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (7, 0.120000, 'GST 12% — Home',        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ------------------------------------------------------------------ products
INSERT INTO products (id, name, description, brand, status, category_id, created_at, updated_at) VALUES
 (1,  'Aurora X5 Smartphone',     '6.5-inch AMOLED, 5000mAh battery, 50MP camera.',      'Aurora', 'ACTIVE', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (2,  'Aurora X5 Pro Smartphone', 'Titanium frame, 120Hz LTPO display, 100W charging.',  'Aurora', 'ACTIVE', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (3,  'Nimbus Buds Pro',          'Hybrid ANC true-wireless earbuds, 30h total.',        'Nimbus', 'ACTIVE', 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (4,  'Nimbus Over-Ear 700',      'Adaptive noise cancelling over-ear headphones.',      'Nimbus', 'ACTIVE', 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (5,  'Vertex Laptop 14',         '14-inch thin-and-light, 8-core CPU, 65Wh battery.',   'Vertex', 'ACTIVE', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (6,  'Vertex Mechanical Keyboard','75% hot-swappable board, PBT keycaps.',              'Vertex', 'ACTIVE', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (7,  'Everyday Cotton Tee',      '180 GSM combed cotton crew-neck.',                    'Loom',   'ACTIVE', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (8,  'Slim Fit Denim',           'Stretch denim, mid-rise, tapered leg.',               'Loom',   'ACTIVE', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (9,  'Flowy Summer Dress',       'Rayon blend midi dress with side pockets.',           'Bloom',  'ACTIVE', 6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (10, 'Merino Wool Scarf',        'Lightweight 100% merino, 180x30cm.',                  'Bloom',  'ACTIVE', 6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (11, 'Ceramic Dinner Set',       '16-piece glazed stoneware, dishwasher safe.',         'Hearth', 'ACTIVE', 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (12, 'Memory Foam Pillow',       'Cervical contour pillow with bamboo cover.',          'Hearth', 'ACTIVE', 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO product_variants (id, product_id, sku, variant_name, price_amount, price_currency, weight_grams, active, created_at, updated_at) VALUES
 (1,  1,  'AUR-X5-128-BLK',   '128GB / Midnight',    24999.00, 'INR',  210, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (2,  1,  'AUR-X5-256-BLK',   '256GB / Midnight',    27999.00, 'INR',  210, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (3,  2,  'AUR-X5P-256-TTN',  '256GB / Titanium',    41999.00, 'INR',  225, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (4,  3,  'NIM-BUDS-PRO-WHT', 'White',                7999.00, 'INR',   58, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (5,  3,  'NIM-BUDS-PRO-BLK', 'Black',                7999.00, 'INR',   58, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (6,  4,  'NIM-OE700-BLK',    'Black',               15999.00, 'INR',  254, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (7,  5,  'VTX-LT14-16-512',  '16GB / 512GB',        84999.00, 'INR', 1290, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (8,  6,  'VTX-KB-BROWN',     'Brown switches',       4499.00, 'INR',  760, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (9,  7,  'TEE-BLK-M',        'Black / M',             699.00, 'INR',  180, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (10, 7,  'TEE-BLK-L',        'Black / L',             699.00, 'INR',  190, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (11, 7,  'TEE-WHT-M',        'White / M',             699.00, 'INR',  180, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (12, 8,  'DENIM-32',         'Indigo / 32',          1899.00, 'INR',  520, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (13, 8,  'DENIM-34',         'Indigo / 34',          1899.00, 'INR',  540, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (14, 9,  'DRESS-FLW-S',      'Floral / S',           1599.00, 'INR',  260, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (15, 9,  'DRESS-FLW-M',      'Floral / M',           1599.00, 'INR',  275, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (16, 10, 'SCARF-MRN-GRY',    'Charcoal Grey',        1299.00, 'INR',  120, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (17, 11, 'DINE-SET-16',      '16 pieces',            3499.00, 'INR', 6400, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (18, 12, 'PILLOW-MF-STD',    'Standard',             1199.00, 'INR',  980, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (19, 2,  'AUR-X5P-512-TTN',  '512GB / Titanium',    47999.00, 'INR',  225, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (20, 4,  'NIM-OE700-SLV',    'Silver',              15999.00, 'INR',  254, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ------------------------------------------------------------------ inventory
-- Deliberately uneven across warehouses so allocation has something to decide.
-- TEE-BLK-M (variant 9) holds exactly ONE unit in total: that is the oversell demo SKU.
INSERT INTO inventory_items (variant_id, warehouse_id, on_hand, reserved, reorder_level, version, created_at, updated_at) VALUES
 (1, 1, 12, 0, 4, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (1, 2,  8, 0, 4, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (1, 3,  0, 0, 4, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (2, 1,  5, 0, 2, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (2, 2,  0, 0, 2, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (3, 1,  3, 0, 2, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (3, 3,  4, 0, 2, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (4, 1, 25, 0, 10, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (4, 2, 30, 0, 10, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (4, 3, 10, 0, 10, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (5, 2, 15, 0, 5, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (6, 1,  9, 0, 3, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (6, 3,  6, 0, 3, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (7, 1,  4, 0, 2, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (7, 2,  2, 0, 2, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (8, 1, 40, 0, 10, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (8, 2, 18, 0, 10, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (9, 1,  1, 0, 5, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (9, 2,  0, 0, 5, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (10, 1, 20, 0, 5, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (10, 2, 14, 0, 5, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (11, 1, 11, 0, 5, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (12, 2, 22, 0, 6, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (12, 3,  9, 0, 6, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (13, 1, 16, 0, 6, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (14, 1,  7, 0, 3, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (14, 3,  5, 0, 3, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (15, 2, 12, 0, 3, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (16, 3, 30, 0, 8, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (17, 1,  6, 0, 2, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (17, 2,  6, 0, 2, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (18, 1, 35, 0, 10, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (18, 2,  0, 0, 10, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (18, 3, 15, 0, 10, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (19, 2,  2, 0, 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 (20, 1,  8, 0, 3, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ------------------------------------------------------------------ discounts
INSERT INTO discounts (code, description, type, discount_value, max_discount_amount, max_discount_currency,
                       min_order_value_amount, min_order_value_currency, category_id,
                       starts_at, ends_at, usage_limit, per_customer_limit, times_redeemed, active, version,
                       created_at, updated_at) VALUES
 ('SAVE10',  '10% off, capped at Rs.500, min order Rs.999', 'PERCENTAGE_OFF', 10.0000, 500.00, 'INR',  999.00, 'INR', NULL,
  TIMESTAMP '2025-01-01 00:00:00', TIMESTAMP '2030-12-31 23:59:59', 1000, 3, 0, TRUE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('FLAT200', 'Flat Rs.200 off on orders above Rs.1500',      'FLAT_AMOUNT_OFF', 200.0000, NULL, NULL, 1500.00, 'INR', NULL,
  TIMESTAMP '2025-01-01 00:00:00', TIMESTAMP '2030-12-31 23:59:59', 500, 2, 0, TRUE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('AUDIO15', '15% off Audio category only, capped at Rs.2000','PERCENTAGE_OFF', 15.0000, 2000.00, 'INR', NULL, NULL, 3,
  TIMESTAMP '2025-01-01 00:00:00', TIMESTAMP '2030-12-31 23:59:59', NULL, NULL, 0, TRUE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
 ('EXPIRED5','Deliberately expired, for the 422 demo',        'PERCENTAGE_OFF', 5.0000, NULL, NULL, NULL, NULL, NULL,
  TIMESTAMP '2024-01-01 00:00:00', TIMESTAMP '2024-12-31 23:59:59', NULL, NULL, 0, TRUE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ------------------------------------------------------------------ identity sequences
-- Explicit ids above do not advance the identity generators, so they are restarted past
-- the seeded range. Valid on both H2 (PostgreSQL mode) and PostgreSQL 10+.
ALTER TABLE roles            ALTER COLUMN id RESTART WITH 100;
ALTER TABLE users            ALTER COLUMN id RESTART WITH 100;
ALTER TABLE warehouses       ALTER COLUMN id RESTART WITH 100;
ALTER TABLE categories       ALTER COLUMN id RESTART WITH 100;
ALTER TABLE products         ALTER COLUMN id RESTART WITH 100;
ALTER TABLE product_variants ALTER COLUMN id RESTART WITH 100;
