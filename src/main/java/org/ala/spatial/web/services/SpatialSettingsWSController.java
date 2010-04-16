package org.ala.spatial.web.services;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import org.ala.spatial.analysis.tabulation.SPLFilter;
import org.ala.spatial.analysis.tabulation.SamplingService;
import org.ala.spatial.analysis.tabulation.SpeciesListIndex;
import org.ala.spatial.util.Layer;
import org.ala.spatial.util.SpatialSettings;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 *
 * @author ajay
 */
@Controller
@RequestMapping("/ws/spatial/settings")
public class SpatialSettingsWSController {

    private SpatialSettings ssets;
    private List<Layer> _layers;

    @RequestMapping(value = "/layers/environmental", method = RequestMethod.GET)
    public
    @ResponseBody
    Layer[] envListAsLayers(HttpServletRequest req) {

        ssets = new SpatialSettings();

        _layers = new ArrayList();
        Layer[] _layerlist = ssets.getEnvironmentalLayers();

        for (int i = 0; i < _layerlist.length; i++) {
            _layers.add(_layerlist[i]);
            //System.out.println("Layer: " + _layerlist[i].name + " - " + _layerlist[i].display_name);

        }

        return _layerlist;
    }

    @RequestMapping(value = "/layers/environmental/string", method = RequestMethod.GET)
    public
    @ResponseBody
    String environmentalLayersAsString(HttpServletRequest req) {

        ssets = new SpatialSettings();

        StringBuffer sbEnvList = new StringBuffer();

        _layers = new ArrayList();
        Layer[] _layerlist = ssets.getEnvironmentalLayers();

        for (int i = 0; i < _layerlist.length; i++) {
            _layers.add(_layerlist[i]);
            //System.out.println("Layer: " + _layerlist[i].name + " - " + _layerlist[i].display_name);

            sbEnvList.append(_layerlist[i].display_name + "\n");

        }

        return sbEnvList.toString();
    }

    @RequestMapping(value = "/layers/contextual/string", method = RequestMethod.GET)
    public
    @ResponseBody
    String contextualLayersAsString(HttpServletRequest req) {

        ssets = new SpatialSettings();

        StringBuffer sbEnvList = new StringBuffer();

        _layers = new ArrayList();
        Layer[] _layerlist = ssets.getContextualLayers();

        for (int i = 0; i < _layerlist.length; i++) {
            _layers.add(_layerlist[i]);
            //System.out.println("Layer: " + _layerlist[i].name + " - " + _layerlist[i].display_name);

            sbEnvList.append(_layerlist[i].display_name + "\n");

        }

        return sbEnvList.toString();
    }

    @RequestMapping(value = "/layer/{layer}/extents", method = RequestMethod.GET)
    public
    @ResponseBody
    String getLayerExtents(@PathVariable String layer) {
        String extents = "";
        try {
            layer = URLDecoder.decode(layer, "UTF-8");
            String layer_filename = SamplingService.layerDisplayNameToName(layer.replaceAll("_", " "));
            extents = SpeciesListIndex.getLayerExtents(layer_filename);
            if (extents == null) {
                extents = "Layer extents not available.";
            }
        } catch (UnsupportedEncodingException ex) {
            Logger.getLogger(SpatialSettingsWSController.class.getName()).log(Level.SEVERE, null, ex);
        }
        return extents;
    }

    @RequestMapping(value = "/layer/{layer}/metadata", method = RequestMethod.GET)
    public
    @ResponseBody
    String getLayerMetadata(@PathVariable String layer) {
        String metadata = "";
        try {
            layer = URLDecoder.decode(layer, "UTF-8");
            String layer_filename = SamplingService.layerDisplayNameToName(layer.replaceAll("_", " "));
            metadata = SamplingService.getLayerMetaData(layer_filename);
            if (metadata == null) {
                metadata = "Layer metadata not available.";
            }
        } catch (UnsupportedEncodingException ex) {
            Logger.getLogger(SpatialSettingsWSController.class.getName()).log(Level.SEVERE, null, ex);
        }
        return metadata;

    }

    @RequestMapping(value = "/layer/{layer}/splfilter", method = RequestMethod.GET)
    public
    @ResponseBody
    SPLFilter getSPLFilter(@PathVariable String layer) {

        try {

            layer = URLDecoder.decode(layer, "UTF-8");

            Layer l = null;

            ssets = new SpatialSettings();

            Layer[] _layerlist = ssets.getEnvironmentalLayers();

            for (int i = 0; i < _layerlist.length; i++) {
                if (_layerlist[i].display_name.equalsIgnoreCase(layer)) {
                    l = _layerlist[i];
                }
            }

            if (l == null) {
                _layerlist = ssets.getContextualLayers();

                for (int i = 0; i < _layerlist.length; i++) {
                    if (_layerlist[i].display_name.equalsIgnoreCase(layer)) {
                        l = _layerlist[i];
                    }
                }
            }
            return SpeciesListIndex.getLayerFilter(l);

        } catch (Exception e) {
            e.printStackTrace(System.out);
        }

        return null;
    }
}
