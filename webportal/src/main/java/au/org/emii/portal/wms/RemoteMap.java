/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package au.org.emii.portal.wms;

import au.org.emii.portal.menu.MapLayer;
import au.org.emii.portal.config.xmlbeans.BaseLayer;
import au.org.emii.portal.config.xmlbeans.Discovery;
import au.org.emii.portal.config.xmlbeans.Service;
import au.org.emii.portal.lang.LanguagePack;

/**
 *
 * @author geoff
 */
public interface RemoteMap {

    MapLayer autoDiscover(String name, float opacity, String uri, String version);

    /**
     * Autodiscover a wms servers layers
     * @param name
     * @param opacity
     * @param uri
     * @param version
     * @return
     */
    MapLayer autoDiscover(String id, String name, float opacity, String uri, String version);

    MapLayer baseLayer(BaseLayer baseLayer);

    /**
     * Create a MapLayer instance and test that an image can be read from
     * the URI.
     *
     * Image format, and wms layer name and wms type are automatically obtained from the
     * URI.
     *
     * If there is no version parameter in the uri, we use a sensible
     * default (v1.1.1)
     *
     * @param label Label to use for the menu system
     * @param uri URI to read the map layer from (a GetMap request)
     * @param opacity Opacity value for this layer
     */
    MapLayer createAndTestWMSLayer(String label, String uri, float opacity);

    MapLayer createAndTestWMSLayer(String label, String uri, float opacity, boolean queryable);

    MapLayer createGeoJSONLayer(String label, String uri);

    MapLayer createWKTLayer(String wkt, String label);

    /**
     * Discovery of nested services
     * @param discovery
     * @return
     */
    MapLayer discover(Discovery discovery, boolean displayAllChildren, boolean queryableDisabled, boolean quiet);

    String getDiscoveryErrorMessage();

    String getDiscoveryErrorMessageSimple();

    String getLastUriAttempted();

    int getLastWMSVersionAttempted();

    MapLayer service(Service service);

}
