package contagion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.princeton.cs.algs4.Edge;
import edu.princeton.cs.algs4.EdgeWeightedGraph;
import edu.princeton.cs.algs4.Graph;
import edu.princeton.cs.algs4.ST;

/**
 * Stores the countries, travel routes, and graph representations.
 *
 * @author Benjamin Paul
 */
public class PandemicNetwork {
    private final List<Country> countries;
    private final List<TravelRoute> routes;
    private final Set<TravelRoute> closedRoutes;
    private final ST<String, Integer> countryIndex;
    private EdgeWeightedGraph weightedGraph;
    private Graph hopGraph;

    public PandemicNetwork() {
        countries = new ArrayList<>();
        routes = new ArrayList<>();
        closedRoutes = new HashSet<>();
        countryIndex = new ST<>();
        rebuildGraphs();
    }

    public void addCountry(Country country) {
        if (country == null) {
            throw new IllegalArgumentException("Country cannot be null.");
        }
        if (countryIndex.contains(country.getName())) {
            throw new IllegalArgumentException("Country already exists: " + country.getName());
        }

        countryIndex.put(country.getName(), countries.size());
        countries.add(country);
        rebuildGraphs();
    }

    public void addRoute(TravelRoute route) {
        if (route == null) {
            throw new IllegalArgumentException("Route cannot be null.");
        }
        validateRegisteredCountry(route.getStartCountry());
        validateRegisteredCountry(route.getEndCountry());
        if (route.getStartCountry() == route.getEndCountry()) {
            throw new IllegalArgumentException("A route must connect two different countries.");
        }
        if (route.getDistance() <= 0) {
            throw new IllegalArgumentException("Route distance must be positive.");
        }

        int startId = indexOf(route.getStartCountry().getName());
        int endId = indexOf(route.getEndCountry().getName());
        if (findRoute(startId, endId) != null) {
            throw new IllegalArgumentException("A route already connects those countries.");
        }

        routes.add(route);
        rebuildGraphs();
    }

    public void closeRoute(int countryAId, int countryBId) {
        closedRoutes.add(requireRoute(countryAId, countryBId));
        rebuildGraphs();
    }

    public void reopenRoute(int countryAId, int countryBId) {
        closedRoutes.remove(requireRoute(countryAId, countryBId));
        rebuildGraphs();
    }

    public boolean isRouteOpen(TravelRoute route) {
        return routes.contains(route) && !closedRoutes.contains(route);
    }

    public void rebuildGraphs() {
        weightedGraph = new EdgeWeightedGraph(countries.size());
        hopGraph = new Graph(countries.size());

        for (TravelRoute route : routes) {
            if (!closedRoutes.contains(route)) {
                int startId = indexOf(route.getStartCountry().getName());
                int endId = indexOf(route.getEndCountry().getName());
                weightedGraph.addEdge(new Edge(startId, endId, route.getDistance()));
                hopGraph.addEdge(startId, endId);
            }
        }
    }

    public Country getCountry(int id) {
        validateCountryId(id);
        return countries.get(id);
    }

    public Country getCountry(String name) {
        return getCountry(indexOf(name));
    }

    public boolean contains(String name) {
        return name != null && countryIndex.contains(name);
    }

    public int indexOf(String name) {
        if (!contains(name)) {
            throw new IllegalArgumentException("Unknown country: " + name);
        }
        return countryIndex.get(name);
    }

    public String nameOf(int id) {
        return getCountry(id).getName();
    }

    public List<Country> getCountries() {
        return Collections.unmodifiableList(countries);
    }

    public List<TravelRoute> getRoutes() {
        return Collections.unmodifiableList(routes);
    }

    public EdgeWeightedGraph getWeightedGraph() {
        return weightedGraph;
    }

    public Graph getHopGraph() {
        return hopGraph;
    }

    private void validateRegisteredCountry(Country country) {
        if (country == null || !contains(country.getName())
                || getCountry(country.getName()) != country) {
            throw new IllegalArgumentException("Route countries must be added to the network first.");
        }
    }

    private void validateCountryId(int id) {
        if (id < 0 || id >= countries.size()) {
            throw new IllegalArgumentException("Unknown country ID: " + id);
        }
    }

    private TravelRoute requireRoute(int countryAId, int countryBId) {
        validateCountryId(countryAId);
        validateCountryId(countryBId);
        TravelRoute route = findRoute(countryAId, countryBId);
        if (route == null) {
            throw new IllegalArgumentException("No route connects those countries.");
        }
        return route;
    }

    private TravelRoute findRoute(int countryAId, int countryBId) {
        for (TravelRoute route : routes) {
            int startId = indexOf(route.getStartCountry().getName());
            int endId = indexOf(route.getEndCountry().getName());
            if ((startId == countryAId && endId == countryBId)
                    || (startId == countryBId && endId == countryAId)) {
                return route;
            }
        }
        return null;
    }
}
