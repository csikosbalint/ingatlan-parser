package org.ingatlan;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.DomNode;
import com.gargoylesoftware.htmlunit.html.HtmlAnchor;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.json.JSONArray;
import org.json.JSONObject;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class App implements RequestHandler<Map<String, String>, Object> {
    public static final String domain = "https://ingatlan.com/";

    public static final Region region = Region.of(System.getenv("AWS_REGION"));
    public static final S3Client s3 = S3Client.builder().region(region).build();
    public static final CloudWatchClient cw = CloudWatchClient.builder().region(region).build();

    public static final String time = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);

    @Override
    public Object handleRequest(Map<String, String> input, Context context) {
        String filter = input.get("filter");
        String bucket = input.get("bucket");
        String search = domain + "szukites/" + filter;
        String base = "ingatlan-" + filter;
        String folder = base + "/" + time + "/";
        System.out.println(search);
        System.out.println(folder);
        WebClient client = new WebClient();
        client.getOptions().setCssEnabled(false);
        client.getOptions().setJavaScriptEnabled(false);

        int currentPage = 1;
        String searchUrl = search + "?page=" + currentPage;
        HtmlPage page = null;
        try {
            page = client.getPage(searchUrl);
        } catch (IOException e) {
            e.printStackTrace();
        }
        int maxPage = Integer.parseInt(
                getValueOrDefault(
                        page.getFirstByXPath("//div[@class='pagination__page-number']"),
                        "1 / 1 oldal")
                        .replaceAll(" ", "")
                        .split("/")[1]
                        .replaceAll("oldal", ""));
        List<HtmlElement> list = new LinkedList<>();
        while (currentPage <= maxPage) {
            try {
                Thread.sleep(2000);
                List<HtmlElement> pageList = page.getByXPath("//div[@data-id]");
                System.out.println("page " + currentPage + "/" + maxPage + " found " + pageList.size());
                list.addAll(pageList);
                currentPage++;
                searchUrl = search + "?page=" + currentPage;
                page = client.getPage(searchUrl);
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
        // today
        Map<String, JSONObject> jsons =
                list.stream()
                        .collect(Collectors.toMap(this::getID, this::getJSON, (first, current) -> {
                            System.out.println("duplicate: " + current);
                            JSONArray links = (JSONArray) first.get("links");
                            String link = (String) ((JSONArray) current.get("links")).get(0);
                            links.put(link);
                            return first;
                        }));
        DescriptiveStatistics stats = new DescriptiveStatistics();

        // prices
        List<Double> prices =
                jsons.values().stream()
                        .map(this::getPrice).collect(Collectors.toList());

        prices.forEach(stats::addValue);

        double std = stats.getStandardDeviation();
        double median = stats.getPercentile(50);

        List<String> nsl = List.of(filter.split("\\+"));

        String ns = nsl.subList(2, nsl.size()).toString();

        // yesterday
        Set<String> yesterdayObjectKeys = s3.listObjects(
                        ListObjectsRequest.builder()
                                .bucket(bucket)
                                .prefix(
                                        base + "/" +
                                                ZonedDateTime.ofInstant(
                                                                Instant.now().minus(1, ChronoUnit.DAYS),
                                                                ZoneOffset.UTC)
                                                        .format(DateTimeFormatter.ISO_INSTANT)
                                                        .split("T")[0])
                                .build())
                .contents()
                .stream()
                .map(this::getID)
                .collect(Collectors.toSet());
        Set<String> todayObjectKeys = new HashSet<>(jsons.keySet());

        Set<String> newToday = new HashSet<>(todayObjectKeys);
        newToday.removeAll(yesterdayObjectKeys);
        if (newToday.size() == jsons.size()) {
            // every ad is new means: no data from yesterday
            newToday = new HashSet<>();
        }

        Set<String> soldYesterday = new HashSet<>(yesterdayObjectKeys);
        soldYesterday.removeAll(todayObjectKeys);

        // Metrics
        cw.putMetricData(PutMetricDataRequest.builder()
                .metricData(
                        MetricDatum.builder()
                                .dimensions(Dimension.builder()
                                        .name("Sold")
                                        .value("M_HUF")
                                        .build())
                                .metricName("Stats")
                                .unit(StandardUnit.NONE)
                                .timestamp(Instant.parse(time))
                                .value(Double.parseDouble(String.valueOf(soldYesterday.size())))
                                .build()
                )
                .namespace(ns)
                .build());
        cw.putMetricData(PutMetricDataRequest.builder()
                .metricData(
                        MetricDatum.builder()
                                .dimensions(Dimension.builder()
                                        .name("New")
                                        .value("M_HUF")
                                        .build())
                                .metricName("Stats")
                                .unit(StandardUnit.NONE)
                                .timestamp(Instant.parse(time))
                                .value(Double.parseDouble(String.valueOf(newToday.size())))
                                .build()
                )
                .namespace(ns)
                .build());
        cw.putMetricData(PutMetricDataRequest.builder()
                .metricData(
                        MetricDatum.builder()
                                .dimensions(Dimension.builder()
                                        .name("DeviationLow")
                                        .value("M_HUF")
                                        .build())
                                .metricName("Stats")
                                .unit(StandardUnit.NONE)
                                .timestamp(Instant.parse(time))
                                .value(median - std)
                                .build()
                )
                .namespace(ns)
                .build());
        cw.putMetricData(PutMetricDataRequest.builder()
                .metricData(
                        MetricDatum.builder()
                                .dimensions(Dimension.builder()
                                        .name("Median")
                                        .value("M_HUF")
                                        .build())
                                .metricName("Stats")
                                .unit(StandardUnit.NONE)
                                .timestamp(Instant.parse(time))
                                .value(median)
                                .build()
                )
                .namespace(ns)
                .build());
        cw.putMetricData(PutMetricDataRequest.builder()
                .metricData(
                        MetricDatum.builder()
                                .dimensions(Dimension.builder()
                                        .name("Deviation")
                                        .value("M_HUF")
                                        .build())
                                .metricName("Stats")
                                .unit(StandardUnit.NONE)
                                .timestamp(Instant.parse(time))
                                .value(std)
                                .build()
                )
                .namespace(ns)
                .build());
        cw.putMetricData(PutMetricDataRequest.builder()
                .metricData(
                        MetricDatum.builder()
                                .dimensions(Dimension.builder()
                                        .name("DeviationHigh")
                                        .value("M_HUF")
                                        .build())
                                .metricName("Stats")
                                .unit(StandardUnit.NONE)
                                .timestamp(Instant.parse(time))
                                .value(median + std)
                                .build()
                )
                .namespace(ns)
                .build());
        cw.putMetricData(PutMetricDataRequest.builder()
                .metricData(
                        MetricDatum.builder()
                                .dimensions(Dimension.builder()
                                        .name("Quantity")
                                        .value("UNIQUE_AD")
                                        .build())
                                .metricName("Stats")
                                .unit(StandardUnit.COUNT)
                                .timestamp(Instant.parse(time))
                                .value((double) stats.getValues().length)
                                .build()
                )
                .namespace(ns)
                .build());

        // persist in S3
        jsons.forEach((key, value) -> {
            try {
                s3.putObject(
                        PutObjectRequest.builder().bucket(bucket).key(folder + key + ".json").build(),
                        RequestBody.fromString(value.toString()));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        s3.close();
        return null;
    }

    private Double getPrice(JSONObject e) {
        JSONArray prices = (JSONArray) e.get("price");
        String priceText = prices.get(prices.length() - 1).toString();
        return Double.parseDouble(priceText);
    }

    private String getID(HtmlElement e) {
        String address = getValueOrDefault(
                e.getFirstByXPath(".//div[@class='listing__address']"),
                "noaddress");
        if (address.isBlank()) {
            throw new RuntimeException("No unique id");
        }
        String areaSize = getValueOrDefault(
                e.getFirstByXPath(".//div[@class='listing__parameter listing__data--area-size']"),
                "0");
        String roomCount = getValueOrDefault(
                e.getFirstByXPath(".//div[@class='listing__parameter listing__data--room-count']"),
                "0");
        String balconySize = getValueOrDefault(
                e.getFirstByXPath(".//div[@class='listing__parameter listing__data--balcony-size']"),
                "0");

        return
                (areaSize.trim().split(" ")[0] +
                        address
                                .replaceAll("[^A-Za-z0-9ÁáÉéÓóÖöŐőÜüŰűÚúÍí ]", "") +
                        roomCount.replaceAll(" ", "").replaceAll("szoba", "") +
                        balconySize.trim().split(" ")[0]
                )
                        .replaceAll(" ", "")
                        .toUpperCase(Locale.ROOT);
    }

    private String getID(S3Object e) {
        return e.key().split("/")[e.key().split("/").length - 1].replaceAll(".json", "");
    }

    private JSONObject getJSON(HtmlElement e) {
        String address = getValueOrDefault(
                e.getFirstByXPath(".//div[@class='listing__address']"),
                "noaddress").trim();
        if (address.isBlank()) {
            throw new RuntimeException("No unique id");
        }
        String price = getValueOrDefault(
                e.getFirstByXPath(".//div[@class='price']"),
                "-1").trim().split(" ")[0];
        String areaSize = getValueOrDefault(
                e.getFirstByXPath(".//div[@class='listing__parameter listing__data--area-size']"),
                "0").trim().split(" ")[0];
        String roomCount = getValueOrDefault(
                e.getFirstByXPath(".//div[@class='listing__parameter listing__data--room-count']"),
                "0")
                .replaceAll("[A-Za-zÁáÉéÓóÖöŐőÜüŰűÚúÍí ]", "");
        Integer rooms =
                Arrays.stream(roomCount.split("\\+"))
                        .map(Integer::parseInt)
                        .collect(Collectors.toList())
                        .stream()
                        .reduce(0, Integer::sum);
        String balconySize = getValueOrDefault(
                e.getFirstByXPath(".//div[@class='listing__parameter listing__data--balcony-size']"),
                "0")
                .trim().split(" ")[0];

        String link =
                domain +
                        ((HtmlAnchor) e.getFirstByXPath(".//a[@class='listing__link js-listing-active-area']"))
                                .getAttribute("href");
        return new JSONObject()
                .put("price", (new JSONArray()).put(price))
                .put("area", areaSize)
                .put("address", address)
                .put("rooms", (new JSONObject()).put("desc", roomCount).put("count", String.valueOf(rooms)))
                .put("balcony", balconySize)
                .put("links", (new JSONArray()).put(link));
    }

    private String getValueOrDefault(DomNode value, String defaultValue) {
        return value == null ? defaultValue : value.getFirstChild().getNodeValue();
    }
}
