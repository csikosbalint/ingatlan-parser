SELECT DISTINCT gid_a gid1
FROM (SELECT /*+ NO_MERGE  */
             DISTINCT a.sdo_gid gid_a,
                      b.sdo_gid gid_b
      FROM cities_sdoindex a,
           road_sdoindex b
      WHERE gid_b = 1234
        AND a.sdo_code = b.sdo_code),
WHERE sdo_geom.relate('cities', gid_a, 'ANYINTERACT',
      'road', 1234) <> 'FALSE'; 
