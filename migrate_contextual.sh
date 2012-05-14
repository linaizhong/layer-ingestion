#!/bin/bash
export SSH_USERNAME=fle13g
export LAYER_ID=990
export LAYER_SHORT_NAME=alwc4

export DEV_SERVER=ala-devmaps.vm.csiro.au
export SHAPE_DIR="/data/ala/data/layers/ready/shape"

export DEV_DB_JDBC_URL="jdbc:postgresql://ala-devmaps-db.vm.csiro.au:5432/layersdb"
export DEV_DB_USERNAME=postgres
export DEV_DB_PASSWORD=postgres
export PROD_DB_JDBC_URL="jdbc:postgresql://ala-maps-db.vic.csiro.au:5432/layersdb"
export PROD_DB_USERNAME=postgres
export PROD_DB_PASSWORD=postgres
export PROD_DB_HOST="ala-maps-db.vic.csiro.au"
export PROD_DB_NAME="layersdb"


export GEOSERVERBASEURL="http://localhost:8082/geoserver"
export GEOSERVERUSERNAME="admin"
export GEOSERVERPASSWORD="at1as0f0z"

export JAVA_CLASSPATH="./layer-ingestion-1.0-SNAPSHOT:./lib/*"

echo "Copy shape file from dev server" \
&& scp ${SSH_USERNAME}@${DEV_SERVER}:${SHAPE_DIR}/${LAYER_SHORT_NAME}.shp ${SHAPE_DIR} \
&& echo "Change mode of copied shapefile" \
&& chmod 777 ${SHAPE_DIR}/${LAYER_SHORT_NAME}.shp \
&& echo "Change ownership of copied shapefile" \
&& chown tomcat:10 ${SHAPE_DIR}/${LAYER_SHORT_NAME}.shp \
&& echo "copy database entries from dev database" \
&& java -Xmx10G -cp "${JAVA_CLASSPATH}" MigrateLayerDatabaseEntries "${LAYER_ID}" "${DEV_DB_JDBC_URL}" "${DEV_DB_USERNAME}" "${DEV_DB_PASSWORD}" "${PROD_DB_JDBC_URL}" "${PROD_DB_USERNAME}" "${PROD_DB_PASSWORD}" \
&& echo "Create database table from shapefile" \
&& shp2pgsql -I -s 4326 "${SHAPE_DIR}/${LAYER_SHORT_NAME}.shp" "${LAYER_ID}" | psql -h "${PROD_DB_HOST}" -d "${PROD_DB_NAME}" -U "${PROD_DB_USERNAME}"
&& echo "Create objects from layer" \
&& java -Xmx10G -cp "${JAVA_CLASSPATH}" au.org.ala.layers.ingestion.contextual.ContextualObjectCreator "${LAYER_ID}" "${PROD_DB_USERNAME}" "${PROD_DB_PASSWORD}" "${PROD_DB_JDBC_URL}" \
&& echo "Load layer in geoserver" \
&& java -Xmx10G -cp "${JAVA_CLASSPATH}" au.org.ala.layers.ingestion.PostgisTableGeoserverLoader "${GEOSERVERBASEURL}" "${GEOSERVERUSERNAME}" "${GEOSERVERPASSWORD}" "${LAYER_ID}" "${LAYER_SHORT_NAME}" "${LAYER_DESCRIPTION}"


#&& echo "Download sld file from dev db" \
#&& wget http://spatial-dev.ala.org.au/geoserver/rest/styles/${LAYER_ID}_style.sld \



# Analysis files
# Tabulation data
# Layer thumbnails



