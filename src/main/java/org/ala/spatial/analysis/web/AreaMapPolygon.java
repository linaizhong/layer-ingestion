package org.ala.spatial.analysis.web;

import au.org.emii.portal.composer.ContextualLayerListComposer;
import au.org.emii.portal.composer.MapComposer;
import au.org.emii.portal.composer.UtilityComposer;
import au.org.emii.portal.menu.MapLayer;
import au.org.emii.portal.settings.SettingsSupplementary;
import au.org.emii.portal.util.LayerUtilities;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.ala.spatial.gazetteer.GazetteerPointSearch;
import org.ala.spatial.util.CommonData;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.PostMethod;
import org.zkoss.zhtml.Messagebox;
import org.zkoss.zk.ui.Page;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zul.Button;
import org.zkoss.zul.Radio;
import org.zkoss.zul.Radiogroup;
import org.zkoss.zul.Textbox;
import org.zkoss.zul.Vbox;

/**
 *
 * @author Adam
 */
public class AreaMapPolygon extends UtilityComposer {

    SettingsSupplementary settingsSupplementary;
    private Textbox displayGeom;
    String layerName;
    Textbox txtLayerName;
    Button btnOk;
    Button btnClear;
    Button btnAddLayer;
    ContextualLayersAutoComplete autoCompleteLayers;
    String treeName, treePath, treeMetadata;
    int treeSubType;
    Radio rAddLayer;
    Vbox vbxLayerList;
    Radiogroup rgPolygonLayers;
 //   List<Radio> radioItems;
    @Override
    public void afterCompose() {
        super.afterCompose();
        loadLayerSelection();
        txtLayerName.setValue(getMapComposer().getNextAreaLayerName("My Area"));
    }

    public void onClick$btnAddLayer(Event event) {
         if(treeName != null) {
            getMapComposer().addWMSLayer(treeName,
                            treePath,
                            (float) 0.75, treeMetadata, treeSubType);

            getMapComposer().updateUserLogMapLayer("env - tree - add", /*joLayer.getString("uid")+*/"|"+treeName);
        

            btnOk.setDisabled(false);
            btnClear.setDisabled(false);


            Radio rNewLayer;
            rNewLayer = new Radio(treeName);
            rNewLayer.setValue(treeName);
            rNewLayer.setParent(rgPolygonLayers);

            Radio rSelectedLayer = rgPolygonLayers.getSelectedItem();
            rgPolygonLayers.insertBefore(rNewLayer, rSelectedLayer);
//            if (rSelectedLayer != null) {
//                rSelectedLayer.setChecked(false);
//                rSelectedLayer.setSelected(false);
//            }
            rNewLayer.setSelected(true);
            rNewLayer.setChecked(true);
       
        }

    }

    public void onClick$btnOk(Event event) {
        this.detach();
    }

    public void onClick$btnClear(Event event) {
        MapComposer mc = getThisMapComposer();
        if(layerName != null && mc.getMapLayer(layerName) != null) {
            mc.removeLayer(layerName);
        }
        String script = mc.getOpenLayersJavascript().addFeatureSelectionTool();
        mc.getOpenLayersJavascript().execute(mc.getOpenLayersJavascript().iFrameReferences + script);
        displayGeom.setValue("");
        btnOk.setDisabled(true);
        btnClear.setDisabled(true);
    }

    public void onClick$btnCancel(Event event) {
        MapComposer mc = getThisMapComposer();
        if(layerName != null && mc.getMapLayer(layerName) != null) {
            mc.removeLayer(layerName);
        }
        this.detach();
    }

    public void onCheck$rgPolygonLayers(Event event) {
        
          Radio selectedItem = rgPolygonLayers.getSelectedItem();
          if (selectedItem == rAddLayer) {
              vbxLayerList.setVisible(true);
          }
          else {
              vbxLayerList.setVisible(false);
              //Add option to add extra layer if not available
               if (rAddLayer == null) {
                rAddLayer = new Radio("Add Other Layer ...");
                rAddLayer.setValue("Add Other Layer ...");
                rAddLayer.setParent(rgPolygonLayers);
                Radio rSelectedLayer = rgPolygonLayers.getSelectedItem();
                rgPolygonLayers.insertBefore(rAddLayer,null);
              }
              //Add and remove layer to set as top layer
              String layerName = selectedItem.getValue();
              MapComposer mc = getThisMapComposer();
              MapLayer ml = mc.getMapLayer(layerName);
              mc.removeLayer(layerName);
              mc.activateLayer(ml, true);
          }
    }


      public void loadLayerSelection() {
        try {

            Radio rSelectedLayer = (Radio) getFellowIfAny("rSelectedLayer");

            List<MapLayer> layers = getMapComposer().getContextualLayers();
            if (layers.size() > 0 ) {
                for (int i = 0; i < layers.size(); i++) {

                    MapLayer lyr = layers.get(i);
                    Radio rAr = new Radio(lyr.getDisplayName());
                    rAr.setId(lyr.getDisplayName().replaceAll(" ", ""));
                    rAr.setValue(lyr.getDisplayName());
                    rAr.setParent(rgPolygonLayers);

                    if (i == 0) {
                        rAr.setSelected(true);
                    }
                    rgPolygonLayers.insertBefore(rAr, rSelectedLayer);
                }
                
                rAddLayer = new Radio("Add Other Layer ...");
                rAddLayer.setValue("Add Other Layer ...");
                rAddLayer.setParent(rgPolygonLayers);
                rgPolygonLayers.insertBefore(rAddLayer,rSelectedLayer);
                rSelectedLayer.setSelected(true);
            }
            else {
                vbxLayerList.setVisible(true);
                btnOk.setDisabled(true);
                btnClear.setDisabled(true);
            }
            
        } catch (Exception e) {
            
        }
    }

    public void onChange$autoCompleteLayers(Event event) {
        treeName = null;
        //btnOk.setDisabled(true);
        
        ContextualLayerListComposer llc = (ContextualLayerListComposer) getFellow("layerTree").getFellow("contextuallayerswindow");

        if (autoCompleteLayers.getItemCount() > 0 && autoCompleteLayers.getSelectedItem() != null) {
            JSONObject jo = (JSONObject) autoCompleteLayers.getSelectedItem().getValue();
            String metadata = "";

            metadata = CommonData.satServer + "/alaspatial/layers/" + jo.getString("uid");

            setLayer(jo.getString("displayname"), jo.getString("displaypath"), metadata, 
                    jo.getString("type").equalsIgnoreCase("environmental")?LayerUtilities.GRID:LayerUtilities.CONTEXTUAL);
        } else {
            JSONObject joLayer = JSONObject.fromObject(llc.tree.getSelectedItem().getTreerow().getAttribute("lyr"));
            if (!joLayer.getString("type").contentEquals("class")) {

                String metadata = CommonData.satServer + "/alaspatial/layers/" + joLayer.getString("uid");

                setLayer(joLayer.getString("displayname"), joLayer.getString("displaypath"), metadata,
                        joLayer.getString("type").equalsIgnoreCase("environmental")?LayerUtilities.GRID:LayerUtilities.CONTEXTUAL);
            } else {
                String classAttribute = joLayer.getString("classname");
                String classValue = joLayer.getString("displayname");
                String layer = joLayer.getString("layername");
                String displaypath = joLayer.getString("displaypath") + "&cql_filter=(" + classAttribute + "='" + classValue + "');include";
                //Filtered requests don't work on
                displaypath = displaypath.replace("gwc/service/", "");
                // Messagebox.show(displaypath);
                String metadata = CommonData.satServer + "/alaspatial/layers/" + joLayer.getString("uid");

                setLayer(layer + " - " + classValue, displaypath, metadata,
                        joLayer.getString("type").equalsIgnoreCase("environmental")?LayerUtilities.GRID:LayerUtilities.CONTEXTUAL);
            }

            //close parent if it is 'addlayerwindow'
            try {
                getRoot().getFellow("addlayerwindow").detach();
            } catch (Exception e) {}
        }
    }

    public void setLayer(String name, String displaypath, String metadata, int subType) {
        treeName = name;
        treePath = displaypath;
        treeMetadata = metadata;
        treeSubType = subType;

        //fill autocomplete text
        autoCompleteLayers.setText(name);

        //clear selection on tree
         ContextualLayerListComposer llc = (ContextualLayerListComposer) getFellow("layerTree").getFellow("contextuallayerswindow");
        llc.tree.clearSelection();

       // btnOk.setDisabled(false);
    }

    /**
     * Searches the gazetter at a given point and then maps the polygon feature
     * found at the location (for the current top contextual layer).
     * @param event 
     */
    public void onSearchPoint(Event event) {
        String searchPoint = (String) event.getData();
        String lon = searchPoint.split(",")[0];
        String lat = searchPoint.split(",")[1];
        Object llist = CommonData.getLayerListJSONArray();
        JSONArray layerlist = JSONArray.fromObject(llist);
        MapComposer mc = getThisMapComposer();

        List<MapLayer> activeLayers = getPortalSession().getActiveLayers();
        Boolean searchComplete = false;
        for (int i = 0; i < activeLayers.size(); i++) {
            MapLayer ml = activeLayers.get(i);

            String activeLayerName = "none";
            if (ml.getUri() != null)
                activeLayerName = ml.getUri().replaceAll("^.*ALA:", "").replaceAll("&.*", "");
            System.out.println("ACTIVE LAYER: " + activeLayerName);
            if (ml.isDisplayed()) {
                for (int j = 0; j < layerlist.size(); j++) {
                    if (searchComplete) {
                        break;
                    }

                    JSONObject jo = layerlist.getJSONObject(j);
                    // System.out.println("********" + jo.getString("name"));
                    if (ml != null && jo.getString("type") != null
                            && jo.getString("type").length() > 0
                            && jo.getString("type").equalsIgnoreCase("contextual")
                            && jo.getString("name").equalsIgnoreCase(activeLayerName)) {

                        
                        System.out.println(ml.getName());
                        String featureURI = GazetteerPointSearch.PointSearch(lon, lat, activeLayerName, CommonData.geoServer);
                        System.out.println(featureURI);
                        if (featureURI == null) {
                            continue;
                        }
                        //if it is a filtered layer, expect the filter as part of the new uri.
//                        boolean passedFilterCheck = true;
//                        try {
//                            String filter = ml.getUri().replaceAll("^.*cql_filter=", "").replaceFirst("^.*='", "").replaceAll("'.*", "");
//                            if (filter != null && filter.length() > 0) {
//                                System.out.println(filter.toLowerCase());
//                                passedFilterCheck = featureURI.toLowerCase().contains(filter.toLowerCase().replace(" ", "_"));
//                            }
//                        } catch (Exception e) {
//                        }
//                        if (!passedFilterCheck) {
//                            continue;
//                        }


                        //add feature to the map as a new layer
                        String feature_text = getWktFromURI(featureURI, true);

                        String json = readGeoJSON(featureURI);
                        String wkt = wktFromJSON(json);
                        if (wkt.contentEquals("none")) {
                            continue;
                          //  break;
                        } else {
                            searchComplete = true;
                            displayGeom.setValue(feature_text);
                            //mc.removeFromList(mc.getMapLayer("Active Area"));
                            layerName = (mc.getMapLayer(txtLayerName.getValue()) == null)?txtLayerName.getValue():mc.getNextAreaLayerName(txtLayerName.getValue());
                            MapLayer mapLayer = mc.addWKTLayer(wkt, layerName, txtLayerName.getValue());
                            updateSpeciesList(false);
                            //searchPoint.setValue("");
                            //setInstructions(null, null);

                            btnOk.setDisabled(false);
                            btnClear.setDisabled(false);
                            break;

                        }
                    }
                }
            }
        }
    }
    
     /**
     * updates species list analysis tab with refreshCount
     */
    void updateSpeciesList(boolean populateSpeciesList) {
//        try {
//            FilteringResultsWCController win =
//                    (FilteringResultsWCController) getMapComposer().getFellow("leftMenuAnalysis").getFellow("analysiswindow").getFellow("sf").getFellow("selectionwindow").getFellow("speciesListForm").getFellow("popup_results");
//            //if (!populateSpeciesList) {
//            win.refreshCount();
//            //} else {
//            //    win.onClick$refreshButton2();
//            // }
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//       // updateAreaLabel();
    }

    /**
     * Gets the main pages controller so we can add a
     * drawing tool to the map
     * @return MapComposer = map controller class
     */
    private MapComposer getThisMapComposer() {

        MapComposer mapComposer = null;
        Page page = getPage();
        mapComposer = (MapComposer) page.getFellow("mapPortalPage");

        return mapComposer;
    }

  

      /**
     * transform json string with geometries into wkt.
     *
     * extracts 'shape_area' if available and assigns it to storedSize.
     *
     * @param json
     * @return
     */
    private String wktFromJSON(String json) {
        try {
            JSONObject obj = JSONObject.fromObject(json);
            JSONArray geometries = obj.getJSONArray("geometries");
            String wkt = "";
            for (int i = 0; i < geometries.size(); i++) {
                String coords = geometries.getJSONObject(i).getString("coordinates");

                if (geometries.getJSONObject(i).getString("type").equalsIgnoreCase("multipolygon")) {
                    wkt += coords.replace("]]],[[[", "))*((").replace("]],[[", "))*((").replace("],[", "*").replace(",", " ").replace("*", ",").replace("[[[[", "MULTIPOLYGON(((").replace("]]]]", ")))");

                } else {
                    wkt += coords.replace("],[", "*").replace(",", " ").replace("*", ",").replace("[[[", "POLYGON((").replace("]]]", "))").replace("],[", "),(");
                }

                wkt = wkt.replace(")))MULTIPOLYGON(", ")),");
            }
            return wkt;
        } catch (JSONException e) {
            return "none";
        }
    }

       /**
     * get Active Area as WKT string, from a layer name and feature class
     *
     * @param layer name of layer as String
     * @param classification value of feature classification
     * @param register_shape true to register the shape with alaspatial shape register
     * @return
     */
    String getWktFromURI(String layer, boolean register_shape) {
        String feature_text = "";//DEFAULT_AREA;

        if (!register_shape) {
            String json = readGeoJSON(layer);
            return feature_text = wktFromJSON(json);
        }

        try {
            String uri = layer;
            String gaz = "gazetteer/";
            int i1 = uri.indexOf(gaz);
            int i2 = uri.indexOf("/", i1 + gaz.length() + 1);
            int i3 = uri.lastIndexOf(".json");
            String table = uri.substring(i1 + gaz.length(), i2);
            String value = uri.substring(i2 + 1, i3);
            //test if available in alaspatial
            HttpClient client = new HttpClient();
            PostMethod get = new PostMethod(CommonData.satServer + "/alaspatial/species/shape/lookup");
            get.addParameter("table", table);
            get.addParameter("value", value);
            get.addRequestHeader("Accept", "text/plain");
            int result = client.executeMethod(get);
            String slist = get.getResponseBodyAsString();
            System.out.println("register table and value with alaspatial: " + slist);

            if (slist != null && result == 200) {
                feature_text = "LAYER(" + layer + "," + slist + ")";

                return feature_text;
            }
        } catch (Exception e) {
            System.out.println("no alaspatial shape for layer: " + layer);
            e.printStackTrace();
        }
        try {
            //class_name is same as layer name
            String json = readGeoJSON(layer);
            feature_text = wktFromJSON(json);

            if (!register_shape) {
                return feature_text;
            }

            //register wkt with alaspatial and use LAYER(layer name, id)
            HttpClient client = new HttpClient();
            //GetMethod get = new GetMethod(sbProcessUrl.toString()); // testurl
            PostMethod get = new PostMethod(CommonData.satServer + "/alaspatial/species/shape/register");
            get.addParameter("area", feature_text);
            get.addRequestHeader("Accept", "text/plain");
            int result = client.executeMethod(get);
            String slist = get.getResponseBodyAsString();
            System.out.println("register wkt shape with alaspatial: " + slist);

            feature_text = "LAYER(" + layer + "," + slist + ")";
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("SelectionController.getLayerGeoJsonAsWkt(" + layer + "): " + feature_text);
        return feature_text;
    }

     private String readGeoJSON(String feature) {
        StringBuffer content = new StringBuffer();

        try {
            // Construct data

            // Send data
            URL url = new URL(feature);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.connect();

            // Get the response
            BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = rd.readLine()) != null) {
                content.append(line);
            }
            conn.disconnect();
        } catch (Exception e) {
        }
        return content.toString();
    }


}
