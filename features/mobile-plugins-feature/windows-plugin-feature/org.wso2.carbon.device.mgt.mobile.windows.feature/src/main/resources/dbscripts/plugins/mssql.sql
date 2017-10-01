-- -----------------------------------------------------
-- Table `WINDOWS_FEATURE`
-- -----------------------------------------------------
IF NOT  EXISTS (SELECT * FROM SYS.OBJECTS WHERE OBJECT_ID = OBJECT_ID(N'[DBO].[WIN_FEATURE]') AND TYPE IN (N'U'))
CREATE TABLE WIN_FEATURE (
  ID INTEGER IDENTITY(1,1) NOT NULL,
  CODE VARCHAR(45) NOT NULL,
  NAME VARCHAR(100) NULL,
  DESCRIPTION VARCHAR(200) NULL,
  PRIMARY KEY (ID)
);

-- -----------------------------------------------------
-- Table `WINDOWS_DEVICE`
-- -----------------------------------------------------
IF NOT  EXISTS (SELECT * FROM SYS.OBJECTS WHERE OBJECT_ID = OBJECT_ID(N'[DBO].[WIN_DEVICE]') AND TYPE IN (N'U'))
CREATE  TABLE WIN_DEVICE (
  DEVICE_ID VARCHAR(45) NOT NULL,
  CHANNEL_URI VARCHAR(100) NULL DEFAULT NULL,
  DEVICE_INFO TEXT NULL DEFAULT NULL,
  IMEI VARCHAR(45) NULL DEFAULT NULL,
  IMSI VARCHAR(45) NULL DEFAULT NULL,
  OS_VERSION VARCHAR(45) NULL DEFAULT NULL,
  DEVICE_MODEL VARCHAR(45) NULL DEFAULT NULL,
  VENDOR VARCHAR(45) NULL DEFAULT NULL,
  LATITUDE VARCHAR(45) NULL DEFAULT NULL,
  LONGITUDE VARCHAR(45) NULL DEFAULT NULL,
  SERIAL VARCHAR(45) NULL DEFAULT NULL,
  MAC_ADDRESS VARCHAR(45) NULL DEFAULT NULL,
  DEVICE_NAME VARCHAR(100) NULL DEFAULT NULL,
  PRIMARY KEY (DEVICE_ID)
);

-- -----------------------------------------------------
-- Table `WINDOWS_ENROLLMENT_TOKEN`
-- -----------------------------------------------------
IF NOT  EXISTS (SELECT * FROM SYS.OBJECTS WHERE OBJECT_ID = OBJECT_ID(N'[DBO].[WINDOWS_ENROLLMENT_TOKEN]') AND TYPE IN (N'U'))
CREATE TABLE WINDOWS_ENROLLMENT_TOKEN (
  ID INTEGER IDENTITY(1,1) NOT NULL,
  TENANT_DOMAIN VARCHAR(45) NOT NULL,
  TENANT_ID INTEGER DEFAULT 0,
  ENROLLMENT_TOKEN VARCHAR (100) NULL DEFAULT NULL,
  DEVICE_ID VARCHAR (100) NOT NULL,
  USERNAME VARCHAR (45) NOT NULL,
  OWNERSHIP VARCHAR (45) NULL DEFAULT NULL,
  PRIMARY KEY (ID)
);