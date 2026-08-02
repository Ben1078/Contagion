package contagion;

/**
 * Represents a travel route between two countries.
 *
 * @author YU-JIE CHEN
 */
public class TravelRoute {

    private Country startCountry;
    private Country endCountry;
    private int distance;

    public TravelRoute(Country startCountry, Country endCountry, int distance) {
        this.startCountry = startCountry;
        this.endCountry = endCountry;
        this.distance = distance;
    }

    public Country getStartCountry() {
        return startCountry;
    }

    public Country getEndCountry() {
        return endCountry;
    }

    public int getDistance() {
        return distance;
    }

    @Override
    public String toString() {
        return startCountry.getName() + " -> "
                + endCountry.getName()
                + " (" + distance + " km)";
    }
}
