INSERT INTO products (id, name, description, image_url, price_minor, currency, active) VALUES
('11111111-1111-4111-8111-111111111111', 'Basmati Rice 5kg', 'Aged long-grain basmati rice', '/img/basmati-5kg.png', 65000, 'INR', true),
('22222222-2222-4222-8222-222222222222', 'Toor Dal 2kg', 'Split pigeon peas', '/img/toor-dal-2kg.png', 32000, 'INR', true),
('33333333-3333-4333-8333-333333333333', 'Groundnut Oil 1L', 'Filtered groundnut oil', '/img/groundnut-oil-1l.png', 18500, 'INR', true),
('44444444-4444-4444-8444-444444444444', 'Assam Tea 500g', 'Strong CTC Assam tea leaves', '/img/assam-tea-500g.png', 27000, 'INR', true),
('55555555-5555-4555-8555-555555555555', 'Detergent Powder 4kg', 'Front-load compatible detergent', '/img/detergent-4kg.png', 41000, 'INR', true),
('66666666-6666-4666-8666-666666666666', 'Steel Water Bottle 1L', 'Vacuum insulated steel bottle', '/img/bottle-1l.png', 89000, 'INR', true),
('77777777-7777-4777-8777-777777777777', 'Cotton Bath Towel', '450 GSM cotton bath towel', '/img/towel.png', 52000, 'INR', true),
('88888888-8888-4888-8888-888888888888', 'Notebook Pack of 6', '200-page ruled notebooks', '/img/notebooks-6.png', 24000, 'INR', false)
ON CONFLICT (id) DO NOTHING;
