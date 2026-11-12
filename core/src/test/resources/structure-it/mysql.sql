-- docker run -d --rm --name dg-my -e MYSQL_ROOT_PASSWORD=pw -e MYSQL_DATABASE=app -p 53306:3306 mysql:8.4
-- docker exec -i dg-my mysql -uroot -ppw app < core/src/test/resources/structure-it/mysql.sql
CREATE TABLE customers (id int AUTO_INCREMENT PRIMARY KEY, email varchar(120) NOT NULL, UNIQUE KEY customers_email (email));
CREATE TABLE orders (
  id bigint AUTO_INCREMENT PRIMARY KEY,
  customer_id int NOT NULL,
  total decimal(10,2) NOT NULL DEFAULT 0,
  status varchar(20) NOT NULL DEFAULT 'new' CHECK (status IN ('new','paid')),
  CONSTRAINT positive_total CHECK (total >= 0),
  CONSTRAINT orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE,
  KEY orders_status (status)
);
CREATE TRIGGER orders_bi BEFORE INSERT ON orders FOR EACH ROW SET NEW.status = lower(NEW.status);
CREATE VIEW big_orders AS SELECT id, total FROM orders WHERE total > 100;
DELIMITER //
CREATE FUNCTION order_total(customer int) RETURNS decimal(10,2) DETERMINISTIC READS SQL DATA COMMENT 'Sum of a customer''s orders'
BEGIN
  RETURN (SELECT COALESCE(SUM(total), 0) FROM orders WHERE customer_id = customer);
END//
CREATE PROCEDURE close_orders(IN customer int, OUT closed int)
BEGIN
  UPDATE orders SET status = 'paid' WHERE customer_id = customer;
  SET closed = ROW_COUNT();
END//
DELIMITER ;
