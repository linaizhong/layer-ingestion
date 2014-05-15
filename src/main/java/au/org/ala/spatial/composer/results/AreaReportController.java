package au.org.ala.spatial.composer.results;

import au.com.bytecode.opencsv.CSVReader;
import au.org.ala.spatial.composer.quicklinks.SamplingEvent;
import au.org.ala.spatial.composer.quicklinks.SpeciesListEvent;
import au.org.ala.spatial.dto.AreaReportItemDTO;
import au.org.ala.spatial.dto.AreaReportItemDTO.ListType;
import au.org.ala.spatial.data.Query;
import au.org.ala.spatial.data.QueryUtil;
import au.org.ala.spatial.data.SpeciesListUtil;
import au.org.ala.spatial.logger.RemoteLogger;
import au.org.ala.spatial.util.CommonData;
import au.org.ala.spatial.util.SelectedArea;
import au.org.ala.spatial.util.Util;
import au.org.emii.portal.composer.MapComposer;
import au.org.emii.portal.composer.UtilityComposer;

import au.org.emii.portal.util.LayerUtilities;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.zkoss.util.resource.Labels;
import org.zkoss.zhtml.Filedownload;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.*;

import javax.swing.event.ListDataEvent;
import java.io.StringReader;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static au.org.ala.spatial.dto.AreaReportItemDTO.ExtraInfoEnum;

/**
 * This class supports the Area Report in the spatial portal.
 * <p/>
 * DM 20/02/2013 - rewrite of this to allow for update of fields in the UI once
 * a query has finished. Previously these updates only happened when all queries
 * had returned.
 * <p/>
 * NC 17/10/2013 - rewrite to display everything nicely in a table. But update mechanism through
 * changing the model behind the view.
 *
 * @author adam
 * @author Dave Martin
 */
public class AreaReportController extends UtilityComposer {

    private static final String VIEW_RECORDS = "View Records";
    public static final int MAX_GAZ_POINT = 5000;
    private static Logger logger = Logger.getLogger(AreaReportController.class);
    RemoteLogger remoteLogger;

    String[] speciesDistributionText = null;
    String[] speciesChecklistText = null;
    String[] areaChecklistText = null;
    Window window = null;
    public String pid;
    boolean addedListener = false;

    SelectedArea selectedArea = null;
    String areaName = "Area Report";
    String areaDisplayName = "Area Report";
    String areaSqKm = null;
    boolean includeEndemic;
    double[] boundingBox = null;
    HashMap<String, String> data = new HashMap<String, String>();
    Div divWorldNote;

    JSONArray gazPoints = null;

    JSONArray pointsOfInterest = null;

    Map<String, Future<Map<String, String>>> futures = null;
    long futuresStart = -1;
    ExecutorService pool = null;
    List<String> firedEvents = null;

    //Items for the list of configured facets
    Grid facetsValues;
    ChangableSimpleListModel areaReportListModel;
    Map<String, AreaReportItemDTO> reportModelMap;

    public boolean shouldIncludeEndemic() {
        return includeEndemic;
    }

    public void setReportArea(SelectedArea sa, String name, String displayname, String areaSqKm, double[] boundingBox, boolean includeEndemic) {
        this.selectedArea = sa;
        sa.getReducedWkt();     //initialize reduced area

        this.areaName = name;
        this.areaDisplayName = displayname;
        this.areaSqKm = areaSqKm;
        this.boundingBox = boundingBox;
        this.includeEndemic = includeEndemic;
        ((Caption) getFellow("cTitle")).setLabel(displayname);

        if (name.equals("Current extent")) {
            addListener();
        }

        try {
            startQueries();
            String extras = "";
            extras += "areaSqKm: " + areaSqKm;
            extras += ";boundingBox: " + boundingBox;
            remoteLogger.logMapAnalysis(displayname, "Tool - Area Report", areaName + "__"  + sa.getReducedWkt(), "", "", pid, extras, "0");
        } catch (Exception e) {
            //logger.error("error logging", e);
        }


        // start checking of completed threads
        Events.echoEvent("checkFutures", this, null);
    }

    @Override
    public void detach() {
        getMapComposer().getLeftmenuSearchComposer().removeViewportEventListener("filteringResults");
        super.detach();
    }

    void addListener() {
        if (!addedListener) {
            addedListener = true;
            // register for viewport changes
            EventListener el = new EventListener() {
                public void onEvent(Event event) throws Exception {
                    selectedArea = new SelectedArea(null, getMapComposer().getViewArea());
                }
            };
            getMapComposer().getLeftmenuSearchComposer().addViewportEventListener("filteringResults", el);
        }
    }


    boolean isTabOpen() {
        return true; // getMapComposer().getPortalSession().getCurrentNavigationTab()
        // == PortalSession.LINK_TAB;
    }

    /**
     * Set up the map for the model that is used to construct the area report table.
     *
     * @return an ordered map of hte maps that need to collect the sampled values
     */
    private Map<String, AreaReportItemDTO> setUpModelMap(boolean isWorldSelected) {
        Map<String, AreaReportItemDTO> values = new LinkedHashMap<String, AreaReportItemDTO>();
        String worldSuffix = isWorldSelected ? "*" : "";
        //area        
        values.put("area", new AreaReportItemDTO("Area (sq km)"));
        //species
        values.put("species" + worldSuffix, new AreaReportItemDTO("Number of species"));
        //spatially valid species 
        values.put("spatialSpecies", new AreaReportItemDTO("Number of species - spatially valid only"));
        if (includeEndemic) {
            //endemic
            values.put("endemicSpecies", new AreaReportItemDTO("Number of endemic species"));
            values.put("spatialEndemicSpecies", new AreaReportItemDTO("Number of endemic species - spatially valid only"));
        }
        //occurrences
        values.put("occurrences" + worldSuffix, new AreaReportItemDTO("Occurrences"));
        //spatially valid occurrences
        values.put("spatialOccurrences", new AreaReportItemDTO("Occurrences - spatially valid only"));
        //expert distribution
        values.put("expertDistributions", new AreaReportItemDTO("Expert distributions"));
        //checklist areas
        values.put("checklistArea", new AreaReportItemDTO("Checklist areas"));
        //checklist species 
        values.put("checklistSpecies", new AreaReportItemDTO("Checklist species"));
        //biostor documents
        values.put("biostor", new AreaReportItemDTO("Biostor documents"));
        //gazetteer points
        values.put("gazetteer", new AreaReportItemDTO("Gazetteer points"));
        //points of interest
        if (CommonData.displayPointsOfInterest) {
            values.put("poi", new AreaReportItemDTO("Points of interest"));
        }
        //configured facets
        values.put("configuedFacets", new AreaReportItemDTO(""));
        return values;
    }


    void startQueries() {

        final boolean worldAreaSelected = CommonData.WORLD_WKT.equals(selectedArea.getWkt());

        reportModelMap = setUpModelMap(worldAreaSelected);
        areaReportListModel = new ChangableSimpleListModel(new ArrayList(reportModelMap.values()));
        facetsValues.setModel(areaReportListModel);
        //Set the renderer that is responsible for the pretties and associating actions to the buttons
        facetsValues.setRowRenderer(new RowRenderer() {

            @Override
            public void render(Row row, Object data, int item_idx) throws Exception {
                //data should be a map of facet result information
                if (data instanceof AreaReportItemDTO) {
                    final AreaReportItemDTO dto = (AreaReportItemDTO) data;
                    row.appendChild(new Label(dto.getTitle()));
                    row.appendChild(new Label(dto.getCount()));
                    //check for the buttons to display
                    if (dto.getExtraInfo() != null) {
                        Div newDiv = new Div();
                        final boolean[] gk = dto.isGeospatialKosher() ? new boolean[]{true, false, false} : new boolean[]{true, true, false};
                        final boolean kosher = dto.isGeospatialKosher();
                        for (ExtraInfoEnum type : dto.getExtraInfo()) {
                            switch (type) {
                                case LIST: {
                                    Button b = new Button("List");
                                    b.setZclass("btn btn-mini");
                                    b.addEventListener("onClick", new EventListener() {

                                        @Override
                                        public void onEvent(Event event)
                                                throws Exception {
                                            if (dto.getListType() == ListType.SPECIES) {
                                                new SpeciesListEvent(getMapComposer(), areaName, 1, gk, dto.isEndemic(), dto.getExtraParams()).onEvent(null);
                                            } else if (dto.getListType() == ListType.DISTRIBUTION) {
                                                listDistributions(dto);
                                            } else if (dto.getListType() == ListType.AREA_CHECKLIST) {
                                                listAreaChecklists(dto);
                                            } else if (dto.getListType() == ListType.SPECIES_CHECKLIST) {
                                                listSpeciesChecklists(dto);
                                            } else if (dto.getListType() == ListType.BIOSTOR) {
                                                listBiostor();
                                            }

                                        }

                                    });
                                    newDiv.appendChild(b);
                                    break;
                                }
                                case SAMPLE: {
                                    Button b = new Button("Sample");
                                    b.setZclass("btn btn-mini");
                                    final SamplingEvent sle = new SamplingEvent(getMapComposer(), null, areaName, null, 2, gk);
                                    b.addEventListener("onClick", new EventListener() {

                                        @Override
                                        public void onEvent(Event event)
                                                throws Exception {
                                            sle.onEvent(null);

                                        }

                                    });
                                    newDiv.appendChild(b);
                                    break;
                                }
                                case MAP_ALL: {
                                    //set up the map button                                  
                                    Button b = new Button("Map all");
                                    b.setZclass("btn btn-mini");
                                    b.addEventListener("onClick", new EventListener() {

                                        @Override
                                        public void onEvent(Event event)
                                                throws Exception {
                                            if (dto.getTitle().contains("Gazetteer")) {
                                                mapGazetteer();
                                            } else if (dto.getTitle().equals("Points of interest")) {
                                                onMapPointsOfInterest(event);
                                            } else if (kosher) {
                                                onMapSpeciesKosher(null);
                                            } else {
                                                onMapSpecies(null);
                                            }
                                        }
                                    });
                                    newDiv.appendChild(b);
                                    break;
                                }
                            }
                        }

                        row.appendChild(newDiv);
                    } else {
                        row.appendChild(new Label(""));
                    }
                    if (dto.getUrlDetails() != null) {
                        Vlayout vlayout = new Vlayout();
                        for (Map.Entry<String, String> entry : dto.getUrlDetails().entrySet()) {
                            String urlTitle = entry.getKey();
                            org.zkoss.zul.A viewRecords = new org.zkoss.zul.A(urlTitle);
                            String url = entry.getValue();

                            if (url.startsWith("http")) {
                                viewRecords.setHref(url);
                                viewRecords.setTarget("_blank");
                            } else {
                                final String helpUrl = CommonData.settings.get("help_url") + "/spatial-portal-help/" + url;
                                viewRecords.addEventListener("onClick", new EventListener() {

                                    @Override
                                    public void onEvent(Event event)
                                            throws Exception {
                                        //zAu.send(new zk.Event(zk.Widget.$(jq('$mapPortalPage')[0]), 'openUrl', help_base_url + "/" + page));
                                        Events.echoEvent("openUrl", getMapComposer(), helpUrl);

                                    }

                                });
                            }
                            vlayout.appendChild(viewRecords);
                        }
                        row.appendChild(vlayout);
                    } else {
                        row.appendChild(new Label(""));
                    }
                    //viewRecordsUrl
                }
            }

        });

        divWorldNote.setVisible(worldAreaSelected);

        Callable occurrenceCount = new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                return occurrenceCount(worldAreaSelected, reportModelMap.get("occurrences"));
            }
        };

        Callable occurrenceCountKosher = new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                return occurrenceCountKosher(worldAreaSelected, reportModelMap.get("spatialOccurrences"));
            }
        };

        Callable speciesCount = new Callable<Map<String, Integer>>() {
            @Override
            public Map<String, Integer> call() {
                return speciesCount(worldAreaSelected, reportModelMap.get("species"));
            }
        };

        Callable speciesCountKosher = new Callable<Map<String, Integer>>() {
            @Override
            public Map<String, Integer> call() {
                return speciesCountKosher(worldAreaSelected, reportModelMap.get("spatialSpecies"));
            }
        };

        Callable endemismCount = new Callable<Map<String, Integer>>() {
            @Override
            public Map<String, Integer> call() {
                return endemismCount(worldAreaSelected, reportModelMap.get("endemicSpecies"));
            }
        };

        Callable endemismCountKosher = new Callable<Map<String, Integer>>() {
            @Override
            public Map<String, Integer> call() {
                return endemismCountKosher(worldAreaSelected, reportModelMap.get("spatialEndemicSpecies"));
            }
        };

        Callable speciesDistributions = new Callable<Map<String, Integer>>() {
            @Override
            public Map<String, Integer> call() {
                return intersectWithSpeciesDistributions(reportModelMap.get("expertDistributions"));
            }
        };

        Callable calculatedArea = new Callable<Map<String, String>>() {
            @Override
            public Map<String, String> call() {
                return calculateArea(reportModelMap.get("area"));
            }
        };

        Callable speciesChecklists = new Callable<Map<String, String>>() {
            @Override
            public Map<String, String> call() {
                return intersectWithSpeciesChecklists(reportModelMap.get("checklistArea"), reportModelMap.get("checklistSpecies"));
            }
        };

        Callable gazPoints = new Callable<Map<String, String>>() {
            @Override
            public Map<String, String> call() {
                return countGazPoints(reportModelMap.get("gazetteer"));
            }
        };

        Callable biostor = new Callable<Map<String, String>>() {
            @Override
            public Map<String, String> call() {
                return biostor(reportModelMap.get("biostor"));
            }
        };

        Callable pointsOfInterest = new Callable<Map<String, String>>() {
            @Override
            public Map<String, String> call() {
                return countPointsOfInterest(reportModelMap.get("poi"));
            }
        };

        Callable areaFacets = new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                return facetCounts(reportModelMap.get("configuedFacets"));
            }
        };

        try {
            this.pool = Executors.newFixedThreadPool(9);
            this.futures = new HashMap<String, Future<Map<String, String>>>();
            this.firedEvents = new ArrayList<String>();
            // add all futures
            futures.put("CalculatedArea", pool.submit(calculatedArea));
            futures.put("OccurrenceCount", pool.submit(occurrenceCount));
            futures.put("OccurrenceCountKosher", pool.submit(occurrenceCountKosher));
            futures.put("AreaFacetCounts", pool.submit(areaFacets));
            futures.put("SpeciesCount", pool.submit(speciesCount));
            futures.put("SpeciesCountKosher", pool.submit(speciesCountKosher));
            futures.put("GazPoints", pool.submit(gazPoints));
            futures.put("SpeciesChecklists", pool.submit(speciesChecklists));
            futures.put("SpeciesDistributions", pool.submit(speciesDistributions));
            futures.put("Biostor", pool.submit(biostor));

            if (CommonData.displayPointsOfInterest) {
                futures.put("PointsOfInterest", pool.submit(pointsOfInterest));
            }

            if (includeEndemic) {
                futures.put("EndemicCount", pool.submit(endemismCount));
                futures.put("EndemicCountKosher", pool.submit(endemismCountKosher));
            }

            futuresStart = System.currentTimeMillis();
        } catch (Exception e) {
            logger.error("error setting counts for futures", e);
        }
    }

    public void checkFutures() {
        try {
            logger.debug("Check futures.....");

            for (Map.Entry<String, Future<Map<String, String>>> futureEntry : futures.entrySet()) {
                String eventToFire = "render" + futureEntry.getKey();
                // logger.debug("eventToFire: " + eventToFire + ", is done: " +
                // futureEntry.getValue().isDone());
                if (futureEntry.getValue().isDone() && !firedEvents.contains(eventToFire)) {
                    try {
                        this.getClass().getMethod(eventToFire, Map.class, Boolean.class).invoke(this, futureEntry.getValue().get(), Boolean.TRUE);
                    } catch (Exception e) {
                        logger.error("error firing event: " + eventToFire, e);
                    }
                    firedEvents.add(eventToFire);
                    //inform the list model to update
                    areaReportListModel.setModelChanged();
                }

                // if biostor and greater than timeout....
                if ("Biostor".equals(futureEntry.getKey()) && !futureEntry.getValue().isDone() && (System.currentTimeMillis() - futuresStart) > 10000) {
                    futureEntry.getValue().cancel(true);
                    Map<String, String> biostorData = new HashMap<String, String>();
                    biostorData.put("biostor", "na");
                    renderBiostor(biostorData, Boolean.FALSE);
                    Clients.evalJavaScript("displayBioStorCount('biostorrow','na');");
                }

                // kill anything taking longer than 5 mins
                if (!futureEntry.getValue().isDone() && (System.currentTimeMillis() - futuresStart) > 300000) {
                    futureEntry.getValue().cancel(true);
                    //now set everything not completed to "Request timed out"
                    for (AreaReportItemDTO model : (List<AreaReportItemDTO>) areaReportListModel.getInnerList()) {
                        if (model.isLoading()) {
                            model.setCount("Request timed out");
                        }
                    }
                    areaReportListModel.setModelChanged();
                }
            }
            logger.debug("Fired events: " + firedEvents.size() + ", Futures: " + futures.size());

            boolean allComplete = firedEvents.size() == futures.size();

            if (!allComplete) {
                Thread.sleep(1000);
                Events.echoEvent("checkFutures", this, null);
            } else {
                logger.debug("All futures completed.");
                this.pool.shutdown();
                futures = null;
            }

        } catch (Exception e) {
            logger.error("", e);
        }
    }

    public void setTimedOut(Label lbl) {
        lbl.setValue("Request timed out");
        lbl.setSclass("");
    }

    public void renderOccurrenceCount(Map<String, Object> recordCounts, Boolean complete) {
        logger.debug("1. Rendering occurrence count...");
        if (!complete) {


            return;
        }

        Integer occurrenceCount = (Integer) recordCounts.get("occurrencesCount");

        if (occurrenceCount > 0 && occurrenceCount <= Integer.parseInt(CommonData.settings.getProperty("max_record_count_map"))) {


        } else {


        }
    }

    public void renderOccurrenceCountKosher(Map<String, Object> recordCounts, Boolean complete) {
        logger.debug("2. Rendering occurrence count kosher...");
    }

    public void renderSpeciesCount(Map<String, Integer> recordCounts, Boolean complete) {
        logger.debug("3. Rendering species count...");
    }

    public void renderSpeciesCountKosher(Map<String, Integer> recordCounts, Boolean complete) {
        logger.debug("4. Rendering species count kosher...");
    }

    public void renderEndemicCount(Map<String, Integer> recordCounts, Boolean complete) {
        logger.debug("5. Rendering endemic counts...");
    }

    public void renderEndemicCountKosher(Map<String, String> recordCounts, Boolean complete) {
        logger.debug("6. Rendering endemic kosher counts...");
    }

    public void renderSpeciesDistributions(Map<String, Integer> recordCounts, Boolean complete) {
        logger.debug("7. Rendering species distributions...");
    }

    public void renderSpeciesChecklists(Map<String, String> recordCounts, Boolean complete) {
        logger.debug("8. Rendering checklists...");
    }

    public void renderGazPoints(Map<String, String> recordCounts, Boolean complete) {
        logger.debug("9. Rendering gaz points...");
    }

    public void renderCalculatedArea(Map<String, String> recordCounts, Boolean complete) {
        logger.debug("10. Rendering calculated area...");
    }

    public void renderBiostor(Map<String, String> recordCounts, Boolean complete) {
        logger.debug("11. Rendering biostor...");
    }

    public void renderPointsOfInterest(Map<String, String> recordCounts, Boolean complete) {
        logger.debug("12. Rendering points of interest...");
    }

    public void renderAreaFacetCounts(Map<String, Object> facetCounts, Boolean complete) {
        logger.debug("13. Rendering custom facets...");
        if (complete) {
            areaReportListModel.setModelChanged();
            areaReportListModel.addAll(facetCounts.values());
            //areaReportListModel.setModelChanged();
        }
    }

    Map<String, Integer> endemismCount(boolean worldSelected, AreaReportItemDTO model) {
        Map<String, Integer> countsData = new HashMap<String, Integer>();
        Query sq = QueryUtil.queryFromSelectedArea(null, selectedArea, false, new boolean[]{true, true, false});
        int endemic_count = sq.getEndemicSpeciesCount();
        countsData.put("endemicSpeciesCount", endemic_count);
        model.setCount(String.format("%,d", endemic_count));
        model.setGeospatialKosher(false);
        if (endemic_count > 0) {
            model.setExtraInfo(new ExtraInfoEnum[]{ExtraInfoEnum.LIST});
            model.setListType(ListType.SPECIES);
        }
        model.setEndemic(true);
        return countsData;
    }

    Map<String, Integer> endemismCountKosher(boolean worldSelected, AreaReportItemDTO model) {
        Map<String, Integer> countsData = new HashMap<String, Integer>();
        Query sq2 = QueryUtil.queryFromSelectedArea(null, selectedArea, false, new boolean[]{true, false, false});
        // based the endemic count on the geospatially kosher - endemic is
        // everything if the world is selected
        int count = sq2.getEndemicSpeciesCount();
        countsData.put("endemicSpeciesCountKosher", count);
        model.setCount(String.format("%,d", count));
        if (count > 0) {
            model.setExtraInfo(new ExtraInfoEnum[]{ExtraInfoEnum.LIST});
            model.setListType(ListType.SPECIES);
            model.setEndemic(true);
            model.setGeospatialKosher(true);
        }
        return countsData;
    }

    Map<String, Integer> speciesCount(boolean worldSelected, AreaReportItemDTO model) {

        Map<String, Integer> countsData = new HashMap<String, Integer>();
        try {
            Query sq = QueryUtil.queryFromSelectedArea(null, selectedArea, false, null);
            int results_count = sq.getSpeciesCount();

            if (results_count == 0) {
                countsData.put("speciesCount", 0);
                model.setCount("0");

                return countsData;
            }
            countsData.put("speciesCount", results_count);
            model.setExtraInfo(new ExtraInfoEnum[]{ExtraInfoEnum.LIST});
            model.setListType(ListType.SPECIES);

            model.setGeospatialKosher(false);
            model.setCount(String.format("%,d", results_count));
        } catch (Exception ex) {
            logger.error("error getting speciescount", ex);
        }
        return countsData;
    }

    Map<String, Object> facetCounts(AreaReportItemDTO pmodel) {
        org.zkoss.util.Locales.setThreadLocal(null);
        Map<String, AreaReportItemDTO> countsData = new LinkedHashMap<String, AreaReportItemDTO>();
        logger.debug("Starting to get facet counts for : " + StringUtils.join(CommonData.areaReportFacets));
        for (String f : CommonData.areaReportFacets) {
            //Map<String, Object> fmap = new HashMap<String,Object>();
            AreaReportItemDTO dto = new AreaReportItemDTO();
            int colonIdx = f.indexOf(":");
            String query = colonIdx > 0 ? f : f + ":*";
            Query sq = QueryUtil.queryFromSelectedArea(null, selectedArea, query, false, null);
            int count = sq.getSpeciesCount();
            String label = Labels.getLabel("facet." + f, f);
            //title
            dto.setTitle(label);
            //count
            dto.setCount(String.format("%,d", count));
            //add the appropriate urls
            //check to see if it is a species list
            if (f.startsWith("species_list") && colonIdx > 0) {
                //extract everything to the right of the colon and construct the url
                String dataResourceUid = f.substring(colonIdx + 1);
                String title = SpeciesListUtil.getSpeciesListMap().get(dataResourceUid);
                if (title != null) {
                    dto.setTitle(title);
                }
                dto.addUrlDetails("Full List", CommonData.speciesListServer + "/speciesListItem/list/" + dataResourceUid);
            }
            //url
            //dto.setUrl(CommonData.biocacheWebServer + "/occurrences/search?q=" + sq.getQ() + "&qc=" + sq.getQc());
            if (count > 0) {
                dto.addUrlDetails(VIEW_RECORDS, CommonData.biocacheWebServer + "/occurrences/search?q=" + sq.getQ() + "&qc=" + sq.getQc());
                //areaReportListModel.add(fmap);
                dto.setExtraParams(query);
                dto.setExtraInfo(new ExtraInfoEnum[]{ExtraInfoEnum.LIST});
                dto.setListType(ListType.SPECIES);
            }
            countsData.put(f, dto);
        }
        logger.debug("Facet Counts ::: " + countsData);
        pmodel.setCount("");
        return (Map) countsData;//this data needs to be added to the model on the correct thread...
    }

    Map<String, Integer> speciesCountKosher(boolean worldSelected, AreaReportItemDTO model) {

        Map<String, Integer> countsData = new HashMap<String, Integer>();

        try {
            Query sq = QueryUtil.queryFromSelectedArea(null, selectedArea, false, new boolean[]{true, false, false});
            int results_count_kosher = sq.getSpeciesCount();

            if (results_count_kosher == 0) {
                countsData.put("speciesCountKosher", 0);
                model.setCount("0");
                return countsData;
            }
            countsData.put("speciesCountKosher", results_count_kosher);
            model.setExtraInfo(new ExtraInfoEnum[]{ExtraInfoEnum.LIST});
            //model.put("speciesListEvent", "true");
            model.setListType(ListType.SPECIES);
            model.setGeospatialKosher(true);
            model.setCount(String.format("%,d", results_count_kosher));
        } catch (Exception ex) {
            logger.error("error getting speciesCountKosher", ex);
        }
        return countsData;
    }

    Map<String, Object> occurrenceCount(boolean worldSelected, AreaReportItemDTO model) {

        Map<String, Object> countsData = new HashMap<String, Object>();
        try {
            Query sq = QueryUtil.queryFromSelectedArea(null, selectedArea, false, null);
            int results_count_occurrences = sq.getOccurrenceCount();
            if (results_count_occurrences == 0) {
                countsData.put("occurrencesCount", "0");//delete me
                model.setCount("0");

                return countsData;
            } else {
                model.addUrlDetails(VIEW_RECORDS, CommonData.biocacheWebServer + "/occurrences/search?q=" + sq.getQ() + "&qc=" + sq.getQc());
            }

            countsData.put("occurrencesCount", results_count_occurrences);//delete me
            model.setCount(String.format("%,d", results_count_occurrences));
            //add the info about the buttons to include
            //model.put("button", "MS");
            if (results_count_occurrences > 0 && results_count_occurrences <= Integer.parseInt(CommonData.settings.getProperty("max_record_count_map"))) {
                model.setExtraInfo(new ExtraInfoEnum[]{ExtraInfoEnum.MAP_ALL, ExtraInfoEnum.SAMPLE});
            }
            model.setGeospatialKosher(false);
        } catch (Exception ex) {
            logger.error("error getting occurrences count", ex);
        }
        return countsData;
    }

    Map<String, Object> occurrenceCountKosher(boolean worldSelected, AreaReportItemDTO model) {

        Map<String, Object> countsData = new HashMap<String, Object>();
        try {
            Query sq = QueryUtil.queryFromSelectedArea(null, selectedArea, false, new boolean[]{true, false, false});
            int results_count_occurrences_kosher = sq.getOccurrenceCount();

            if (results_count_occurrences_kosher == 0) {
                countsData.put("occurrencesCountKosher", 0);
                model.setCount("0");

                return countsData;
            } else {
                countsData.put("viewRecordsKosherUrl", CommonData.biocacheWebServer + "/occurrences/search?q=" + sq.getQ() + "&qc=" + sq.getQc());
                model.addUrlDetails(VIEW_RECORDS, CommonData.biocacheWebServer + "/occurrences/search?q=" + sq.getQ() + "&qc=" + sq.getQc());
            }
            model.setExtraInfo(new ExtraInfoEnum[]{ExtraInfoEnum.MAP_ALL, ExtraInfoEnum.SAMPLE});
            model.setGeospatialKosher(true);
            if (results_count_occurrences_kosher > 0 && results_count_occurrences_kosher <= Integer.parseInt(CommonData.settings.getProperty("max_record_count_map"))) {
                countsData.put("occurrencesCountKosher", results_count_occurrences_kosher);
            }
            model.setCount(String.format("%,d", results_count_occurrences_kosher));
        } catch (Exception ex) {
            logger.error("error getting occurrences count", ex);
        }
        return countsData;
    }


    public void onMapSpecies(Event event) {
        try {
            SelectedArea sa = null;
            if (!areaName.equalsIgnoreCase("Current extent")) {
                sa = selectedArea;
            } else {
                sa = new SelectedArea(null, getMapComposer().getViewArea());
            }

            Query query = QueryUtil.queryFromSelectedArea(null, sa, true, null);

            String activeAreaLayerName = getMapComposer().getNextActiveAreaLayerName(areaDisplayName);
            getMapComposer().mapSpecies(query, activeAreaLayerName, "species", -1, LayerUtilities.SPECIES, null, -1, MapComposer.DEFAULT_POINT_SIZE, MapComposer.DEFAULT_POINT_OPACITY,
                    Util.nextColour());

        } catch (Exception e) {
            logger.error("error mapping species in area", e);
        }
    }

    public void onMapSpeciesKosher(Event event) {
        try {
            SelectedArea sa = null;
            if (!areaName.equalsIgnoreCase("Current extent")) {
                sa = selectedArea;
            } else {
                sa = new SelectedArea(null, getMapComposer().getViewArea());
            }

            Query query = QueryUtil.queryFromSelectedArea(null, sa, true, new boolean[]{true, false, false});

            String activeAreaLayerName = getMapComposer().getNextActiveAreaLayerName(areaDisplayName + " geospatial kosher");
            getMapComposer().mapSpecies(query, activeAreaLayerName, "species", -1, LayerUtilities.SPECIES, null, -1, MapComposer.DEFAULT_POINT_SIZE, MapComposer.DEFAULT_POINT_OPACITY,
                    Util.nextColour());

        } catch (Exception e) {
            logger.error("error mapping kosher species in area", e);
        }
    }

    public void onMapPointsOfInterest(Event event) {
        try {
            String activeAreaLayerName = "Points of interest in " + areaDisplayName;

            StringBuilder sb = new StringBuilder();
            sb.append("MULTIPOINT(");

            for (int i = 0; i < pointsOfInterest.size(); i++) {
                JSONObject jsonObjPoi = pointsOfInterest.getJSONObject(i);
                double latitude = jsonObjPoi.getDouble("latitude");
                double longitude = jsonObjPoi.getDouble("longitude");

                sb.append(longitude);
                sb.append(" ");
                sb.append(latitude);

                if (i < pointsOfInterest.size() - 1) {
                    sb.append(",");
                }
            }

            sb.append(")");

            getMapComposer().mapPointsOfInterest(sb.toString(), activeAreaLayerName, activeAreaLayerName);

        } catch (Exception e) {
            logger.error("error mapping points of interest", e);
        }
    }

    static public void open(SelectedArea sa, String name, String displayName, String areaSqKm, double[] boundingBox, boolean includeEndemic) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("includeEndemic", includeEndemic);
        params.put("displayPointsOfInterest", CommonData.displayPointsOfInterest);
        AreaReportController win = (AreaReportController) Executions.createComponents("/WEB-INF/zul/results/AreaReportResults.zul", null, params);
        try {
            win.doOverlapped();
            win.setPosition("center");
            win.setReportArea(sa, name, displayName, areaSqKm, boundingBox, includeEndemic);
        } catch (Exception e) {
            logger.error("error opening AreaReportResults.zul", e);
        }
    }

    public Map<String, Integer> intersectWithSpeciesDistributions(AreaReportItemDTO model) {
        Map<String, Integer> speciesDistributions = new HashMap<String, Integer>();
        try {
            String wkt = selectedArea.getReducedWkt();
            if (wkt.contains("ENVELOPE") && selectedArea.getMapLayer() != null) {
                // use boundingbox
                List<Double> bbox = selectedArea.getMapLayer().getMapLayerMetadata().getBbox();
                double long1 = bbox.get(0);
                double lat1 = bbox.get(1);
                double long2 = bbox.get(2);
                double lat2 = bbox.get(3);
                wkt = "POLYGON((" + long1 + " " + lat1 + "," + long1 + " " + lat2 + "," + long2 + " " + lat2 + "," + long2 + " " + lat1 + "," + long1 + " " + lat1 + "))";
            }
            String[] lines = getDistributionsOrChecklists("distributions", wkt, null, null);

            if (lines == null || lines.length <= 1) {
                speciesDistributions.put("intersectWithSpeciesDistributions", 0);
                model.setCount("0");
                speciesDistributionText = null;
            } else {
                speciesDistributions.put("intersectWithSpeciesDistributions", lines.length - 1);
                model.setCount(Integer.toString(lines.length - 1));
                model.setExtraInfo(new ExtraInfoEnum[]{ExtraInfoEnum.LIST});
                model.setListType(ListType.DISTRIBUTION);
                speciesDistributionText = lines;
            }
        } catch (Exception e) {
            logger.error("error getting intersected distribution areas", e);
        }
        return speciesDistributions;
    }

    public Map<String, String> intersectWithSpeciesChecklists(AreaReportItemDTO areaModel, AreaReportItemDTO spModel) {
        Map<String, String> checklistsCounts = new HashMap<String, String>();
        try {
            String wkt = selectedArea.getReducedWkt();
            if (wkt.contains("ENVELOPE") && selectedArea.getMapLayer() != null) {
                // use boundingbox
                List<Double> bbox = selectedArea.getMapLayer().getMapLayerMetadata().getBbox();
                double long1 = bbox.get(0);
                double lat1 = bbox.get(1);
                double long2 = bbox.get(2);
                double lat2 = bbox.get(3);
                wkt = "POLYGON((" + long1 + " " + lat1 + "," + long1 + " " + lat2 + "," + long2 + " " + lat2 + "," + long2 + " " + lat1 + "," + long1 + " " + lat1 + "))";
            }

            String[] lines = getDistributionsOrChecklists("checklists", wkt, null, null);

            if (lines == null || lines.length <= 1) {
                spModel.setCount("0");
                checklistsCounts.put("intersectWithSpeciesChecklists", "0");
                areaModel.setCount("0");
                checklistsCounts.put("intersectWithAreaChecklists", "0");
                speciesChecklistText = null;
            } else {
                checklistsCounts.put("intersectWithSpeciesChecklists", String.format("%,d", lines.length - 1));
                spModel.setCount(String.format("%,d", lines.length - 1));
                spModel.setExtraInfo(new ExtraInfoEnum[]{ExtraInfoEnum.LIST});
                spModel.setListType(ListType.SPECIES_CHECKLIST);
                areaChecklistText = getAreaChecklists(lines);
                checklistsCounts.put("intersectWithAreaChecklists", String.format("%,d", areaChecklistText.length - 1));
                areaModel.setCount(String.format("%,d", areaChecklistText.length - 1));
                areaModel.setExtraInfo(new ExtraInfoEnum[]{ExtraInfoEnum.LIST});
                areaModel.setListType(ListType.AREA_CHECKLIST);
                speciesChecklistText = lines;
            }
        } catch (Exception e) {
            logger.error("error getting intersected species checklists", e);
        }

        return checklistsCounts;
    }

    public Map<String, String> countGazPoints(AreaReportItemDTO model) {
        Map<String, String> gazPointsCounts = new HashMap<String, String>();
        try {
            String wkt = selectedArea.getReducedWkt();
            if (wkt.contains("ENVELOPE") && selectedArea.getMapLayer() != null) {
                // use boundingbox
                List<Double> bbox = selectedArea.getMapLayer().getMapLayerMetadata().getBbox();
                double long1 = bbox.get(0);
                double lat1 = bbox.get(1);
                double long2 = bbox.get(2);
                double lat2 = bbox.get(3);
                wkt = "POLYGON((" + long1 + " " + lat1 + "," + long1 + " " + lat2 + "," + long2 + " " + lat2 + "," + long2 + " " + lat1 + "," + long1 + " " + lat1 + "))";
            }

            JSONArray ja = getGazPoints(wkt);

            if (ja == null || ja.size() == 0) {
                gazPointsCounts.put("countGazPoints", "0");
                model.setCount("0");
                speciesChecklistText = null;
            } else {
                gazPointsCounts.put("countGazPoints", String.format("%,d", ja.size()));
                model.setCount(String.format("%,d", ja.size()));
                model.setExtraInfo(new ExtraInfoEnum[]{ExtraInfoEnum.MAP_ALL});
                gazPoints = ja;
            }
        } catch (Exception e) {
            logger.error("error with area gaz point count", e);
        }

        return gazPointsCounts;
    }

    public Map<String, String> countPointsOfInterest(AreaReportItemDTO model) {
        Map<String, String> poiCounts = new HashMap<String, String>();
        try {
            String wkt = selectedArea.getReducedWkt();
            if (wkt.contains("ENVELOPE") && selectedArea.getMapLayer() != null) {
                // use boundingbox
                List<Double> bbox = selectedArea.getMapLayer().getMapLayerMetadata().getBbox();
                double long1 = bbox.get(0);
                double lat1 = bbox.get(1);
                double long2 = bbox.get(2);
                double lat2 = bbox.get(3);
                wkt = "POLYGON((" + long1 + " " + lat1 + "," + long1 + " " + lat2 + "," + long2 + " " + lat2 + "," + long2 + " " + lat1 + "," + long1 + " " + lat1 + "))";
            }

            JSONArray ja = getPointsOfInterest(wkt);

            if (ja == null || ja.size() == 0) {
                poiCounts.put("countPointsOfInterest", "0");
                model.setCount("0");
                speciesChecklistText = null;
            } else {
                poiCounts.put("countPointsOfInterest", String.format("%,d", ja.size()));
                model.setCount(String.format("%,d", ja.size()));
                model.setExtraInfo(new ExtraInfoEnum[]{ExtraInfoEnum.MAP_ALL});
                pointsOfInterest = ja;
            }
        } catch (Exception e) {
            logger.error("area report error counting points of interest", e);
        }

        return poiCounts;
    }

    String biostorHtml = null;

    Map<String, String> biostor(AreaReportItemDTO model) {

        Map<String, String> countData = new HashMap<String, String>();

        try {
            String area = selectedArea.getWkt();
            double lat1 = 0;
            double lat2 = 0;
            double long1 = 0;
            double long2 = 0;
            if (area.contains("ENVELOPE") && selectedArea.getMapLayer() != null) {
                // use boundingbox
                List<Double> bbox = selectedArea.getMapLayer().getMapLayerMetadata().getBbox();
                long1 = bbox.get(0);
                lat1 = bbox.get(1);
                long2 = bbox.get(2);
                lat2 = bbox.get(3);
            } else {
                Pattern coord = Pattern.compile("[+-]?[0-9]*\\.?[0-9]* [+-]?[0-9]*\\.?[0-9]*");
                Matcher matcher = coord.matcher(area);

                boolean first = true;
                while (matcher.find()) {
                    String[] p = matcher.group().split(" ");
                    double[] d = {Double.parseDouble(p[0]), Double.parseDouble(p[1])};

                    if (first || long1 > d[0]) {
                        long1 = d[0];
                    }
                    if (first || long2 < d[0]) {
                        long2 = d[0];
                    }
                    if (first || lat1 > d[1]) {
                        lat1 = d[1];
                    }
                    if (first || lat2 < d[1]) {
                        lat2 = d[1];
                    }

                    first = false;
                }
            }

            String biostorurl = "http://biostor.org/bounds.php?";
            biostorurl += "bounds=" + long1 + "," + lat1 + "," + long2 + "," + lat2;

            HttpClient client = new HttpClient();
            client.getHttpConnectionManager().getParams().setConnectionTimeout(5000);
            GetMethod get = new GetMethod(biostorurl);
            get.addRequestHeader("Accept", "application/json, text/javascript, */*");
            get.addRequestHeader("User-Agent", "ALA Spatial Portal");
            int result = client.executeMethod(get);

            biostorHtml = null;
            if (result == HttpStatus.SC_OK) {
                String slist = get.getResponseBodyAsString();
                if (slist != null) {

                    JSONArray list = JSONObject.fromObject(slist).getJSONArray("list");
                    StringBuilder sb = new StringBuilder();
                    sb.append("<ol>");
                    for (int i = 0; i < list.size(); i++) {
                        sb.append("<li>");
                        sb.append("<a href=\"http://biostor.org/reference/");
                        sb.append(list.getJSONObject(i).getString("id"));
                        sb.append("\" target=\"_blank\">");
                        sb.append(list.getJSONObject(i).getString("title"));
                        sb.append("</li>");
                    }
                    sb.append("</ol>");

                    if (list.size() > 0) {
                        biostorHtml = sb.toString();
                    }

                    countData.put("biostor", String.valueOf(list.size()));
                    model.setCount(Integer.toString(list.size()));
                    if (list.size() > 0) {
                        model.setExtraInfo(new ExtraInfoEnum[]{ExtraInfoEnum.LIST});
                        model.setListType(ListType.BIOSTOR);
                        //model.setUrl("http://biostor.org/");
                        model.addUrlDetails("Biostor info", "http://biostor.org/");
                    }
                }
            } else {
                // lblBiostor.setValue("BioStor currently down");
                countData.put("biostor", "Biostor currently down");
                model.setCount("Biostor currently down");
            }

        } catch (Exception e) {
            logger.error("unable to get area info from biostor, is it down?", e);
            // lblBiostor.setValue("BioStor currently down");
            countData.put("biostor", "Biostor currently down");
            model.setCount("Biostor currently down");
        }
        return countData;
    }

    /**
     * Generates data for rendering of distributions table.
     *
     * @param type
     * @param wkt
     * @param lsids
     * @param geom_idx
     * @return
     */
    public static String[] getDistributionsOrChecklists(String type, String wkt, String lsids, String geom_idx) {
        try {
            StringBuilder sbProcessUrl = new StringBuilder();
            sbProcessUrl.append("/" + type);

            HttpClient client = new HttpClient();
            PostMethod post = new PostMethod(CommonData.layersServer + sbProcessUrl.toString()); // testurl
            logger.debug(CommonData.layersServer + sbProcessUrl.toString());
            if (wkt != null) {
                post.addParameter("wkt", wkt);
            }
            if (lsids != null) {
                post.addParameter("lsids", lsids);
            }
            if (geom_idx != null) {
                post.addParameter("geom_idx", geom_idx);
            }
            post.addRequestHeader("Accept", "application/json, text/javascript, */*");
            int result = client.executeMethod(post);
            if (result == 200) {
                String txt = post.getResponseBodyAsString();
                JSONArray ja = JSONArray.fromObject(txt);
                if (ja == null || ja.size() == 0) {
                    return null;
                } else {
                    String[] lines = new String[ja.size() + 1];
                    lines[0] = "SPCODE,SCIENTIFIC_NAME,AUTHORITY_FULL,COMMON_NAME,FAMILY,GENUS_NAME,SPECIFIC_NAME,MIN_DEPTH,MAX_DEPTH,METADATA_URL,LSID,AREA_NAME,AREA_SQ_KM";
                    for (int i = 0; i < ja.size(); i++) {
                        JSONObject jo = ja.getJSONObject(i);
                        String spcode = jo.containsKey("spcode") ? jo.getString("spcode") : "";
                        String scientific = jo.containsKey("scientific") ? jo.getString("scientific") : "";
                        String auth = jo.containsKey("authority_") ? jo.getString("authority_") : "";
                        String common = jo.containsKey("common_nam") ? jo.getString("common_nam") : "";
                        String family = jo.containsKey("family") ? jo.getString("family") : "";
                        String genus = jo.containsKey("genus") ? jo.getString("genus") : "";
                        String name = jo.containsKey("specific_n") ? jo.getString("specific_n") : "";
                        String min = jo.containsKey("min_depth") ? jo.getString("min_depth") : "";
                        String max = jo.containsKey("max_depth") ? jo.getString("max_depth") : "";
                        // String p =
                        // jo.containsKey("pelagic_fl")?jo.getString("pelagic_fl"):"";
                        String md = jo.containsKey("metadata_u") ? jo.getString("metadata_u") : "";
                        String lsid = jo.containsKey("lsid") ? jo.getString("lsid") : "";
                        String area_name = jo.containsKey("area_name") ? jo.getString("area_name") : "";
                        String area_km = jo.containsKey("area_km") ? jo.getString("area_km") : "";
                        String data_resource_uid = jo.containsKey("data_resource_uid") ? jo.getString("data_resource_uid") : "";

                        lines[i + 1] = spcode + "," + wrap(scientific) + "," + wrap(auth) + "," + wrap(common) + "," + wrap(family) + "," + wrap(genus) + "," + wrap(name) + "," + min + "," + max + "," + wrap(md) + "," + wrap(lsid) + "," + wrap(area_name) + "," + wrap(area_km) + "," + wrap(data_resource_uid);
                    }

                    return lines;
                }
            }
        } catch (Exception e) {
            logger.error("error building distribution or checklist csv", e);
        }
        return null;
    }

    public static JSONArray getGazPoints(String wkt) {
        try {
            int limit = Integer.MAX_VALUE;
            String url = CommonData.layersServer + "/objects/inarea/" + CommonData.settings.get("area_report_gaz_field") + "?limit=" + limit;

            HttpClient client = new HttpClient();
            PostMethod post = new PostMethod(url);
            logger.debug(CommonData.layersServer + url);
            if (wkt != null) {
                post.addParameter("wkt", wkt);
            }
            post.addRequestHeader("Accept", "application/json, text/javascript, */*");
            int result = client.executeMethod(post);
            if (result == 200) {
                String txt = post.getResponseBodyAsString();
                return JSONArray.fromObject(txt);
            }
        } catch (Exception e) {
            logger.error("error getting number of gaz points in an area: " + wkt, e);
        }
        return null;
    }

    public static JSONArray getPointsOfInterest(String wkt) {
        try {
            String url = CommonData.layersServer + "/intersect/poi/wkt";

            HttpClient client = new HttpClient();
            PostMethod post = new PostMethod(url);
            if (wkt != null) {
                NameValuePair nvp = new NameValuePair("wkt", wkt);
                post.setRequestBody(new NameValuePair[]{nvp});
            }
            post.addRequestHeader("Accept", "application/json, text/javascript, */*");
            //post.setRequestHeader("Content-Type", "application/wkt");
            int result = client.executeMethod(post);
            if (result == 200) {
                String txt = post.getResponseBodyAsString();
                return JSONArray.fromObject(txt);
            }
        } catch (Exception e) {
            logger.error("error getting points of interest in an area: " + wkt, e);
        }
        return null;
    }

    public static String[] getAreaChecklists(String geom_idx, String lsids, String wkt) {
        try {
            return getAreaChecklists(getDistributionsOrChecklists("checklists", lsids, wkt, geom_idx));
        } catch (Exception e) {
            logger.error("error getting checklists for: " + geom_idx + "," + wkt + "," + lsids, e);
        }
        return null;
    }

    public static String[] getAreaChecklists(String[] records) {
        String[] lines = null;
        try {
            if (records != null) {
                String[][] data = new String[records.length - 1][]; // exclude
                // header
                for (int i = 1; i < records.length; i++) {
                    CSVReader csv = new CSVReader(new StringReader(records[i]));
                    data[i - 1] = csv.readNext();
                    csv.close();
                }
                java.util.Arrays.sort(data, new Comparator<String[]>() {
                    @Override
                    public int compare(String[] o1, String[] o2) {
                        // compare WMS urls
                        return CommonData.getSpeciesChecklistWMSFromSpcode(o1[0])[1].compareTo(CommonData.getSpeciesChecklistWMSFromSpcode(o2[0])[1]);
                    }
                });

                lines = new String[records.length];
                lines[0] = lines[0] = "SPCODE,SCIENTIFIC_NAME,AUTHORITY_FULL,COMMON_NAME,FAMILY,GENUS_NAME,SPECIFIC_NAME,MIN_DEPTH,MAX_DEPTH,METADATA_URL,LSID,AREA_NAME,AREA_SQ_KM,SPECIES_COUNT";
                int len = 1;
                int thisCount = 0;
                for (int i = 0; i < data.length; i++) {
                    thisCount++;
                    if (i == data.length - 1 || !CommonData.getSpeciesChecklistWMSFromSpcode(data[i][0])[1].equals(CommonData.getSpeciesChecklistWMSFromSpcode(data[i + 1][0])[1])) {
                        StringBuilder sb = new StringBuilder();
                        for (int j = 0; j < data[i].length; j++) {
                            if (j > 0) {
                                sb.append(",");
                            }
                            if (j == 0 || (j >= 9 && j != 10)) {
                                sb.append(wrap(data[i][j]));
                            }
                        }
                        sb.append(",").append(thisCount);
                        lines[len] = sb.toString();
                        len++;
                        thisCount = 0;
                    }
                }
                lines = java.util.Arrays.copyOf(lines, len);
            }
        } catch (Exception e) {
            logger.error("error building species checklist", e);
            lines = null;
        }
        return lines;
    }

    public static String[] getDistributionOrChecklist(String spcode) {
        try {
            StringBuilder sbProcessUrl = new StringBuilder();
            sbProcessUrl.append("/distribution/" + spcode);

            HttpClient client = new HttpClient();
            GetMethod get = new GetMethod(CommonData.layersServer + sbProcessUrl.toString()); // testurl
            logger.debug(CommonData.layersServer + sbProcessUrl.toString());
            get.addRequestHeader("Accept", "application/json, text/javascript, */*");
            int result = client.executeMethod(get);
            if (result == 200) {
                String txt = get.getResponseBodyAsString();
                JSONObject jo = JSONObject.fromObject(txt);
                if (jo == null) {
                    return null;
                } else {
                    String[] output = new String[14];
                    // "SPCODE,SCIENTIFIC_NAME,AUTHORITY_FULL,COMMON_NAME,FAMILY,GENUS_NAME,SPECIFIC_NAME,MIN_DEPTH,MAX_DEPTH,METADATA_URL,LSID,AREA_NAME,AREA_SQ_KM";

                    // String spcode = jo.containsKey("spcode") ?
                    // jo.getString("spcode") : "";
                    String scientific = jo.containsKey("scientific") ? jo.getString("scientific") : "";
                    String auth = jo.containsKey("authority_") ? jo.getString("authority_") : "";
                    String common = jo.containsKey("common_nam") ? jo.getString("common_nam") : "";
                    String family = jo.containsKey("family") ? jo.getString("family") : "";
                    String genus = jo.containsKey("genus") ? jo.getString("genus") : "";
                    String name = jo.containsKey("specific_n") ? jo.getString("specific_n") : "";
                    String min = jo.containsKey("min_depth") ? jo.getString("min_depth") : "";
                    String max = jo.containsKey("max_depth") ? jo.getString("max_depth") : "";
                    // String p =
                    // jo.containsKey("pelagic_fl")?jo.getString("pelagic_fl"):"";
                    String md = jo.containsKey("metadata_u") ? jo.getString("metadata_u") : "";
                    String lsid = jo.containsKey("lsid") ? jo.getString("lsid") : "";
                    String area_name = jo.containsKey("area_name") ? jo.getString("area_name") : "";
                    String area_km = jo.containsKey("area_km") ? jo.getString("area_km") : "";
                    String data_resource_id = jo.containsKey("data_resource_uid") ? jo.getString("data_resource_uid") : "";

                    output[0] = spcode;
                    output[1] = scientific;
                    output[2] = auth;
                    output[3] = common;
                    output[4] = family;
                    output[5] = genus;
                    output[6] = name;
                    output[7] = min;
                    output[8] = max;
                    output[9] = md;
                    output[10] = lsid;
                    output[11] = area_name;
                    output[12] = area_km;
                    output[13] = data_resource_id;

                    return output;
                }
            }
        } catch (Exception e) {
            logger.error("error building distributions list", e);
        }
        return null;
    }

    static String wrap(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    public void listDistributions(AreaReportItemDTO model) {
        int c = 0;
        try {
            c = Integer.parseInt(model.getCount().replace(",", ""));
        } catch (Exception e) {
        }
        if (c > 0 && speciesDistributionText != null) {
            try {
                getMapComposer().getFellowIfAny("distributionresults").detach();
            } catch (Exception e) {
            }
            DistributionsController window = (DistributionsController) Executions.createComponents("WEB-INF/zul/results/AnalysisDistributionResults.zul", this, null);

            try {
                window.doModal();
                window.init(speciesDistributionText, "Expert Distributions", model.getCount(), new EventListener() {

                    @Override
                    public void onEvent(Event event) throws Exception {
                        onClick$sdDownload(event);
                    }
                });
            } catch (Exception e) {
                logger.error("error opening expert distributions window", e);
            }
        }
    }

    public void listSpeciesChecklists(AreaReportItemDTO model) {
        int c = 0;
        try {
            c = Integer.parseInt(model.getCount().replace(",", ""));
        } catch (Exception e) {
        }
        if (c > 0 && speciesChecklistText != null) {
            DistributionsController window = (DistributionsController) Executions.createComponents("WEB-INF/zul/results/AnalysisDistributionResults.zul", this, null);

            try {
                window.doModal();
                window.init(speciesChecklistText, "Species Checklists", model.getCount(), new EventListener() {

                    @Override
                    public void onEvent(Event event) throws Exception {
                        onClick$clDownload(event);
                    }
                });
            } catch (Exception e) {
                logger.error("error opening species checklists window", e);
            }
        }
    }

    public void listAreaChecklists(AreaReportItemDTO model) {
        int c = 0;
        //get the areaChecklist model

        try {
            c = Integer.parseInt(model.getCount().replace(",", ""));
        } catch (Exception e) {
        }
        if (c > 0 && areaChecklistText != null) {
            DistributionsController window = (DistributionsController) Executions.createComponents("WEB-INF/zul/results/AnalysisDistributionResults.zul", this, null);

            try {
                window.doModal();
                window.init(areaChecklistText, "Checklist areas", model.getCount(), new EventListener() {

                    @Override
                    public void onEvent(Event event) throws Exception {
                        onClick$aclDownload(event);
                    }
                });
            } catch (Exception e) {
                logger.error("error opening area checklists window", e);
            }
        }
    }

    public void onClick$downloadexpert(Event event) {
        onClick$sdDownload(event);
    }

    public void onClick$sdDownload(Event event) {
        String spid = pid;
        if (spid == null || spid.equals("none")) {
            spid = String.valueOf(System.currentTimeMillis());
        }

        SimpleDateFormat date = new SimpleDateFormat("yyyyMMdd");
        String sdate = date.format(new Date());

        StringBuilder sb = new StringBuilder();
        for (String s : speciesDistributionText) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(s);
        }
        Filedownload.save(sb.toString(), "text/plain", "Species_distributions_" + sdate + "_" + spid + ".csv");
    }

    public void onClick$downloadchecklist(Event event) {
        onClick$clDownload(event);
    }

    public void onClick$clDownload(Event event) {
        String spid = pid;
        if (spid == null || spid.equals("none")) {
            spid = String.valueOf(System.currentTimeMillis());
        }

        SimpleDateFormat date = new SimpleDateFormat("yyyyMMdd");
        String sdate = date.format(new Date());

        StringBuilder sb = new StringBuilder();
        for (String s : speciesChecklistText) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(s);
        }
        Filedownload.save(sb.toString(), "text/plain", "Species_checklists_" + sdate + "_" + spid + ".csv");
    }

    public void onClick$aclDownload(Event event) {
        String spid = pid;
        if (spid == null || spid.equals("none")) {
            spid = String.valueOf(System.currentTimeMillis());
        }

        SimpleDateFormat date = new SimpleDateFormat("yyyyMMdd");
        String sdate = date.format(new Date());

        StringBuilder sb = new StringBuilder();
        for (String s : areaChecklistText) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(s);
        }
        Filedownload.save(sb.toString(), "text/plain", "Area_checklists_" + sdate + "_" + spid + ".csv");
    }

    protected Map<String, String> calculateArea(AreaReportItemDTO model) {

        Map<String, String> areaCalc = new HashMap<String, String>();

        if (areaSqKm != null) {
            areaCalc.put("area", areaSqKm);
            model.setCount(areaSqKm);
            speciesDistributionText = null;
            //model.setUrl("note-area-sq-km");
            model.addUrlDetails("Info", "note-area-sq-km");
            return areaCalc;
        }

        try {
            double totalarea = Util.calculateArea(selectedArea.getWkt());
            DecimalFormat df = new DecimalFormat("###,###.##");

            // lblArea.setValue(String.format("%,d", (int) (totalarea / 1000 /
            // 1000)));
            // data.put("area",String.format("%,f", (totalarea / 1000 / 1000)));
            areaCalc.put("area", df.format(totalarea / 1000 / 1000));
            model.setCount(df.format(totalarea / 1000 / 1000));
            //model.setUrl("note-area-sq-km");
            model.addUrlDetails("Info", "note-area-sq-km");

        } catch (Exception e) {
            logger.error("Error in calculateArea", e);
            areaCalc.put("area", "");
            model.setCount("ERROR...");
        }
        return areaCalc;
    }

    public void onClick$btnCancel(Event event) {
        this.detach();
    }

    public void listBiostor() {
        if (biostorHtml != null) {
            Event ev = new Event("onClick", this, "Biostor Documents\n" + biostorHtml);
            getMapComposer().openHTML(ev);
        }
    }

    private boolean isNumberGreaterThanZero(String value) {
        boolean ret = false;
        try {
            ret = Double.parseDouble(value.replace(",", "")) > 0;
        } catch (Exception e) {
        }
        return ret;
    }

    public void mapGazetteer() {
        try {
            if (gazPoints == null || gazPoints.size() == 0) {
                return;
            }

            StringBuilder sb = new StringBuilder();
            TreeSet<String> columns = new TreeSet<String>();

            // get columns
            for (int i = 0; i < gazPoints.size(); i++) {
                columns.addAll(gazPoints.getJSONObject(i).keySet());
            }

            // write columns, first two are longitude,latitude
            sb.append("longitude,latitude");
            for (String s : columns) {
                if (!s.equals("longitude") && !s.equals("latitude")) {
                    sb.append(",").append(s);
                }
            }

            for (int i = 0; i < gazPoints.size(); i++) {
                sb.append("\n");

                if (gazPoints.getJSONObject(i).containsKey("geometry")) {
                    String geometry = gazPoints.getJSONObject(i).getString("geometry");
                    geometry = geometry.replace("POINT(", "").replace(")", "").replace(" ", ",");
                    sb.append(geometry);
                } else {
                    sb.append(",");
                }

                for (String s : columns) {
                    if (!s.equals("longitude") && !s.equals("latitude")) {
                        String ss = gazPoints.getJSONObject(i).containsKey(s) ? gazPoints.getJSONObject(i).getString(s) : "";
                        sb.append(",\"").append(ss.replace("\"", "\"\"")).append("\"");
                    }
                }
            }

            getMapComposer().featuresCSV = sb.toString();
            getMapComposer().getOpenLayersJavascript().execute("mapFrame.mapPoints('" + StringEscapeUtils.escapeJavaScript(gazPoints.toString()) + "');");
        } catch (Exception e) {
            logger.error("error mapping gaz points from area report", e);
        }
    }

    /**
     * A simple list model object that allows external objects to request change events to be fired.
     *
     * @author Natasha Carter (natasha.carter@csiro.au)
     */
    public class ChangableSimpleListModel extends ListModelList {
        public ChangableSimpleListModel(List data) {
            super(data);
        }

        public void setModelChanged() {

            fireEvent(ListDataEvent.CONTENTS_CHANGED, -1, -1);
        }
    }
}