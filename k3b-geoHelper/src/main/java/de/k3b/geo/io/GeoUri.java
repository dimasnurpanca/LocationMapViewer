/*
 * Copyright (C) 2015 k3b
 *
 * This file is part of de.k3b.android.LocationMapViewer (https://github.com/k3b/LocationMapViewer/) .
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>
 */

package de.k3b.geo.io;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.k3b.geo.api.GeoPointDto;
import de.k3b.geo.api.IGeoPointInfo;

/**
 * Converts between a {@link de.k3b.geo.api.IGeoPointInfo} and a uri string.<br/>
 * Format:<br/>
 * geo:{lat}{,lon{,hight_ignore}}}{?q={lat}{,lon}{,hight_ignore}{(name)}}{&uri=uri}{&id=id}{&d=description}{&z=zmin{&z2=zmax}}{&t=timeOfMeasurement}
 * Example (with {@link de.k3b.geo.io.GeoUri#OPT_FORMAT_REDUNDANT_LAT_LON} set):<br/>
 * geo:12.345,-56.7890123?q=12.345,-56.7890123(name)&z=5&z2=7&uri=uri&d=description&id=id&t=1991-03-03T04:05:06Z
 * <p/>
 * This should be compatible with standard http://tools.ietf.org/html/draft-mayrhofer-geo-uri-00
 * and with googlemap for android. This implementation has aditional non-standard parameters
 * for LocationViewer clients.<br/>
 * <br/>
 * Created by k3b on 13.01.2015.
 */
public class GeoUri {
    /* constants that define behaviour of fromUri and toUri */

    public static final int OPT_DEFAULT = 0;
    /** In toUriString: If set lat/lon is inserted twice: in q= parameter and in geo. <br/>
     * If not lat/lon is only in geo part but not in the q= part*/
    public static final int OPT_FORMAT_REDUNDANT_LAT_LON = 1;
    /** In fromUri: if set tries to get time, location, name for everywhere.<br/>
     * Example: "geo:?d=I was in (Hamburg) located at 53,10 on 1991-03-03T04:05:06Z" would set
     * name, location and time from the description text.
     */
    public static final int OPT_PARSE_INFER_MISSING = 0x100;

    /**
     * Default for url-encoding.
     */
    private static final String DEFAULT_ENCODING = "UTF-8";
    private static final String GEO_SCHEME = "geo:";

    /* regular expressions used by the parser.<br/>
       '(?:"+something+")"' is a non capturing group; "\s" white space */
    private final static String regexpName = "(?:\\s*\\(([^\\(\\)]+)\\))"; // i.e. " (hallo world)"
    private final static Pattern patternName = Pattern.compile(regexpName);
    private final static String regexpDouble = "([+\\-]?[0-9\\.]+)"; // i.e. "-123.456"
    private final static String regexpDoubleOptional = regexpDouble + "?";
    private final static String regexpCommaDouble = "(?:\\s*,\\s*" + regexpDouble + ")"; // i.e. " , +123.456"
    private final static String regexpCommaDoubleOptional = regexpCommaDouble + "?";
    private final static String regexpLatLonAlt = regexpDouble + regexpCommaDouble + regexpCommaDoubleOptional;
    private final static Pattern patternLatLonAlt = Pattern.compile(regexpLatLonAlt);
    private final static Pattern patternTime = Pattern.compile("([12]\\d\\d\\d-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ)");
    /* converter for Datatypes */
    private static final DecimalFormat latLonFormatter = new DecimalFormat("#.#######", new DecimalFormatSymbols(Locale.ENGLISH));
    private static final DateFormat timeFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    /* current state */

    /** formating/parsing options */
    private final int options;

    /** for uri-formatter: next delimiter for a parameter. can be "?" or "&"  */
    private String delim;

    /** create wit options from OPT_xxx */
    public GeoUri(int options) {
        this.options = options;
    }

    /** load IGeopoint from uri-string */
    public IGeoPointInfo fromUri(String uri) {
        return fromUri(uri, new GeoPointDto());
    }

    /** load IGeopoint from uri-string into parseResult. */
    public <TGeo extends GeoPointDto>  TGeo fromUri(String uri, TGeo parseResult) {
        if (uri == null) return null;
        if (!uri.startsWith(GEO_SCHEME)) return null;

        int queryOffset = uri.indexOf("?");

        if (queryOffset >= 0) {
            String query = uri.substring(queryOffset+1);
            uri = uri.substring(0, queryOffset);
            HashMap<String, String> parmLookup = new HashMap<String, String>();
            String[] params = query.split("&");
            for (String param : params) {
                parseAddQueryParamToMap(parmLookup, param);
            }
            parseResult.setDescription(parmLookup.get("d"));
            parseResult.setUri(parmLookup.get("uri"));
            parseResult.setId(parmLookup.get("id"));
            parseResult.setZoomMin(parseZoom(parmLookup.get("z")));
            parseResult.setZoomMax(parseZoom(parmLookup.get("z2")));

            // parameters from standard value and/or infered
            ArrayList<String> whereToSearch = new ArrayList<String>();
            whereToSearch.add(parmLookup.get("q")); // lat lon from q have precedence over url-path
            whereToSearch.add(uri);

            if (isSet(GeoUri.OPT_PARSE_INFER_MISSING)) {
                whereToSearch.add(parseResult.getDescription());
                whereToSearch.addAll(parmLookup.values());
            }

            parseResult.setName(parseFindFromPattern(patternName, parseResult.getName(), whereToSearch));
            parseResult.setTimeOfMeasurement(parseTimeFromPattern(parmLookup.get("t"), whereToSearch));

            parseLatOrLon(parseResult, whereToSearch);
        }
        return parseResult;
    }

    /** parsing helper: set first finding of lat and lon to parseResult */
    private void parseLatOrLon(GeoPointDto parseResult, ArrayList<String> whereToSearch) {
        Matcher m = parseFindWithPattern(patternLatLonAlt, whereToSearch);

        if (m != null) {
            try {
                final String val = m.group(1);
                double lat = parseLatOrLon(val);
                double lon = parseLatOrLon(m.group(2));

                parseResult.setLatitude(lat).setLongitude(lon);
            } catch (ParseException e) {
                e.printStackTrace();
            }
        }
    }

    /** parsing helper: converts a double value from string to double */
    private double parseLatOrLon(String val) throws ParseException {
        return latLonFormatter.parse(val).doubleValue();
    }

    /** parsing helper: Get the first finding of pattern in whereToSearch if currentValue is not set yet.
     * Returns currentValue or content of first matching group of pattern. */
    private String parseFindFromPattern(Pattern pattern, String currentValue, List<String> whereToSearch) {
        if ((currentValue == null) || (currentValue.length() == 0)) {
            Matcher m = parseFindWithPattern(pattern, whereToSearch);
            String found = (m != null) ? m.group(1) : null;
            if (found != null) {
                return found;
            }
        }
        return currentValue;
    }

    /** parsing helper: Get the first datetime finding in whereToSearch if currentValue is not set yet.
     * Returns currentValue or finding as Date . */
    private Date parseTimeFromPattern(String currentValue, List<String> whereToSearch) {
        String match = parseFindFromPattern(patternTime, currentValue, whereToSearch);

        if (match != null) {
            try {
                return timeFormatter.parse(match);
            } catch (ParseException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    /** parsing helper: returns the match of the first finding of pattern in whereToSearch. */
    private Matcher parseFindWithPattern(Pattern pattern, List<String> whereToSearch) {
        for (String candidate : whereToSearch) {
            if (candidate != null) {
                Matcher m = pattern.matcher(candidate);
                while (m.find() && (m.groupCount() > 0)) {
                    return m;
                }
            }
        }
        return null;
    }

    /** parsing helper: converts value into zoom compatible int */
    private int parseZoom(String value) {
        if (value != null) {
            try {
                int result = Integer.parseInt(value);
                if ((result >= 0) && (result < 64)) {
                    return result;
                }
            } catch (Exception ignore) {
            }
        }
        return IGeoPointInfo.NO_ZOOM;
    }

    /** parsing helper: add a found query-parameter to a map for fast lookup */
    private void parseAddQueryParamToMap(HashMap<String, String> parmLookup, String param) {
        if (param != null) {
            String[] keyValue = param.split("=");
            if ((keyValue != null) && (keyValue.length == 2)) {
                try {
                    parmLookup.put(keyValue[0], URLDecoder.decode(keyValue[1], DEFAULT_ENCODING));
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    /**
     * Converts a {@link de.k3b.geo.api.IGeoPointInfo} into uri string representatino.<br/>
     * <br/>
     * Format
     *
     * geo:{lat{,lon{,hight_ignore}}}{?q={lat}{,lon}{,hight_ignore}{(name)}}{&uri=uri}{&id=id}{&d=description}{&z=zmin{&z2=zmax}}{&t=timeOfMeasurement}
     */
    public String toUriString(IGeoPointInfo geoPoint) {
        StringBuffer result = new StringBuffer();
        result.append(GEO_SCHEME);
        formatLatLon(result, geoPoint);

        delim = "?";
        appendQueryParameter(result, "q", formatQuery(geoPoint), false);
        appendQueryParameter(result, "z", geoPoint.getZoomMin());
        appendQueryParameter(result, "z2", geoPoint.getZoomMax());
        appendQueryParameter(result, "uri", geoPoint.getUri(), true);
        appendQueryParameter(result, "d", geoPoint.getDescription(), true);
        appendQueryParameter(result, "id", geoPoint.getId(), true);
        if (geoPoint.getTimeOfMeasurement() != null) {
            appendQueryParameter(result, "t", timeFormatter.format(geoPoint.getTimeOfMeasurement()), false);
        }

        return result.toString();
    }

    /** formatting helper: */
    private void appendQueryParameter(StringBuffer result, String paramName, int paramValue) {
        if (paramValue != IGeoPointInfo.NO_ZOOM) {
            appendQueryParameter(result, paramName, Integer.toString(paramValue), true);
        }
    }

    /** formatting helper: */
    private void appendQueryParameter(StringBuffer result, String paramName, String paramValue, boolean urlEncode) {
        if (paramValue != null) {
            try {
                result.append(delim).append(paramName).append("=");
                if (urlEncode) {
                    paramValue = encode(paramValue);
                }
                result.append(paramValue);
                delim = "&";
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
    }

    /** formatting helper: */
    private void formatLatLon(StringBuffer result, IGeoPointInfo geoPoint) {
        if (geoPoint.getLatitude() != IGeoPointInfo.NO_LAT_LON) {
            result.append(latLonFormatter.format(geoPoint.getLatitude()));
        }
        if (geoPoint.getLongitude() != IGeoPointInfo.NO_LAT_LON) {
            result.append(",").append(latLonFormatter.format(geoPoint.getLongitude()));
        }
    }

    /** formatting helper: */
    private String formatQuery(IGeoPointInfo geoPoint) {
        // {lat{,lon{,hight_ignore}}}{(name)}{|uri{|id}|}{description}
        StringBuffer result = new StringBuffer();

        if (isSet(OPT_FORMAT_REDUNDANT_LAT_LON)) {
            formatLatLon(result, geoPoint);
        }

        if (geoPoint.getName() != null) {
            try {
                result.append("(").append(encode(geoPoint.getName())).append(")");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        if (result.length() == 0) return null;

        return result.toString();
    }

    /** formatting helper: */
    private String encode(String raw) throws UnsupportedEncodingException {
        return URLEncoder.encode(raw, DEFAULT_ENCODING);
    }

    /** return true, if opt is set */
    private boolean isSet(int opt) {
        return ((options & opt) != 0);
    }
}