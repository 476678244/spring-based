CREATE TABLE VEHICLE
(
  VEHICLE_NO VARCHAR(10),
  COLOR VARCHAR(10),
  WHEEL INT,
  SEAT INT,
  PRIMARY KEY (VEHICLE_NO)
);

CREATE TABLE COURSE
(
  ID INT(32) AUTO_INCREMENT,
  TITLE VARCHAR(100),
  BEGIN_DATE TIMESTAMP DEFAULT  CURRENT_TIMESTAMP,
  END_DATE TIMESTAMP DEFAULT  CURRENT_TIMESTAMP,
  FEE INT(8),
  PRIMARY KEY (ID)
);