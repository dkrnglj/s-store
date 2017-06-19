CREATE TABLE MEDEVENTS (
    SUBJECT_ID  BIGINT  NOT NULL,
    ICUSTAY_ID  BIGINT,
    ITEMID      BIGINT  NOT NULL,
    CHARTTIME   VARCHAR(64), --TIMESTAMP WITH TIME ZONE
    ELEMID      BIGINT  NOT NULL,
    REALTIME    VARCHAR(64), --TIMESTAMP WITH TIME ZONE,
    CGID        BIGINT,
    CUID        BIGINT,
    VOLUME      BIGINT,
    DOSE        FLOAT,
    DOSEUOM     VARCHAR(64),
    SOLUTIONID  BIGINT,
    SOLVOLUME   BIGINT,
    SOLUNITS    VARCHAR(64),
    ROUTE       VARCHAR(64),
    STOPPED     VARCHAR(64),
    CONSTRAINT PK_MEDEVENTS PRIMARY KEY (
        SUBJECT_ID, ITEMID, ELEMID, CHARTTIME, REALTIME
    )
);
