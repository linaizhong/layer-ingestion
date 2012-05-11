#!/bin/bash
# Loads a contextual layer from raw .adf data, using the GridClassesBuilder to generate class data which is
# read from disk
# NOTE: The following 5 variables need to be modified for each new layer
export LAYER_ID=111
export LAYER_NAME="ga_bath_rocky" 
export LAYER_DESCRIPTION=
export UNITS="%" 
export ADF_HEADER_FILE="/data/ala/data/layers/raw/ga_bath_rocky/hdr.adf"

export DB_USERNAME="postgres"
export DB_PASSWORD="Passw0rd"
export DB_JDBC_URL="jdbc:postgresql://ala-devmaps-db.vm.csiro.au:5432/layersdb"

export GEOSERVER_BASE_URL="http://localhost:8082/geoserver"
export GEOSERVER_USERNAME="admin"
export GEOSERVER_PASSWORD="at1as0f0z" 

export PROCESS_DIR="/data/ala/data/layers/process"
export SHAPE_DIVA_DIR="/data/ala/data/layers/ready/shape_diva"
export GEOTIFF_DIR="/data/ala/data/layers/ready/geotiff"
export LEGEND_DIR="/data/ala/data/layers/test"

export JAVA_CLASSPATH="./layer-ingestion-1.0-SNAPSHOT.jar:./lib/*"

echo "create process directory" 
&& mkdir -p "${PROCESS_DIR}/${LAYER_NAME}" \
&& echo "convert adf to bil, reprojecting to WGS 84" \
&& gdalwarp -of EHdr -ot Float32 -t_srs EPSG:4326 "${ADF_HEADER_FILE}" "${PROCESS_DIR}/${LAYER_NAME}/${LAYER_NAME}.bil" \
&& echo "convert bil to diva" \
&& java -Xmx10G -cp "${JAVA_CLASSPATH}" org.ala.layers.util.Bil2diva "${PROCESS_DIR}/${LAYER_NAME}/${LAYER_NAME}" "${SHAPE_DIVA_DIR}/${LAYER_NAME}" "${UNITS}" \
&& echo "Run GridClassBuilder tool" \
&& java -Xmx10G -cp "${JAVA_CLASSPATH}" org.ala.layers.grid.GridClassBuilder "${SHAPE_DIVA_DIR}/${LAYER_NAME}" \
&& echo "Copy generated .sld legend file to legend directory" \
&& 
&& echo "Convert diva for polygons into bil"
&& java -Xmx10G -cp "${JAVA_CLASSPATH}" org.ala.layers.util.Diva2bil "${SHAPE_DIVA_DIR}/${LAYER_NAME}/polygons" "${PROCESS_DIR}/${LAYER_NAME}/${LAYER_NAME}" \
&& echo "Convert polygons bil into geotiff"
&& gdal_translate -of GTiff "${PROCESS_DIR}/${LAYER_NAME}/${LAYER_NAME}.bil" "${GEOTIFF_DIR}/${LAYER_NAME}.tif" \
&& echo "Creating layer and fields table entries for layer" \
&& java -Xmx10G -cp "${JAVA_CLASSPATH}" au.org.ala.layers.ingestion.contextual.ContextualFromGridDatabaseLoader "${LAYER_ID}" "${LAYER_NAME}" "${LAYER_DESCRIPTION}" "${UNITS}" "${DIVA_DIR}/${LAYER_NAME}.grd" "${DB_USERNAME}" "${DB_PASSWORD}" "${DB_JDBC_URL}" \
&& echo "Load layer into geoserver" \
&& java -Xmx10G -cp "${JAVA_CLASSPATH}" au.org.ala.layers.ingestion.GeotiffGeoserverLoader "${LAYER_NAME}" "${GEOTIFF_DIR}/${LAYER_NAME}.tif" "${LEGEND_DIR}/${LAYER_NAME}.sld" "${GEOSERVER_BASE_URL}" "${GEOSERVER_USERNAME}" "${GEOSERVER_PASSWORD}"