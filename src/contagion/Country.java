package contagion;

/**
 * Represents a country in the pandemic network.
 *
 * @author YU-JIE CHEN
 */
public class Country {

    private String name;
    private int population;
    private boolean infected;

    public Country(String name, int population) {
        this.name = name;
        this.population = population;
        this.infected = false;
    }

    public String getName() {
        return name;
    }

    public int getPopulation() {
        return population;
    }

    public boolean isInfected() {
        return infected;
    }

    public void setInfected(boolean infected) {
        this.infected = infected;
    }

    @Override
    public String toString() {
        return name + " (Population: " + population + ")";
    }
}
