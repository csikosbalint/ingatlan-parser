SELECT *
FROM fruit
WHERE type='apple' AND type='orange'  -- Noncompliant

CREATE PROCEDURE USER_BY_EMAIL(@email VARCHAR(255)) AS
BEGIN
  EXEC('USE AuthDB; SELECT id FROM user WHERE email = ''' + @email + ''' ;'); -- Sensitive: could inject code using @email
END

SELECT FIRST_NAME, LAST_NAME FROM PERSONS
WHERE LAST_NAME LIKE '%PONT'

SELECT HASHBYTES('SHA1', MyColumn) FROM dbo.MyTable;

DELETE FROM countries
UPDATE employee SET status = 'retired' FROM table1 AS employee

SELECT *
FROM fruit2
WHERE type='apple2' AND type='orange2'  -- Noncompliant2
