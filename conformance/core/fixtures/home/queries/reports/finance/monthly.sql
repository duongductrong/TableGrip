-- connection: Via bastion (key)
SELECT date_trunc('month', at), sum(amount)
FROM payments
GROUP BY 1;
