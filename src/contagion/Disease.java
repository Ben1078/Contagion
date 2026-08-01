package Contagion;

/**
 * Stores information about a disease.
 *
 * @author YU-JIE CHEN
 */
public class disease {

    private String name;
    private double infectionRate;
    private double recoveryRate;

    public disease(String name, double infectionRate, double recoveryRate) {
        this.name = name;
        this.infectionRate = infectionRate;
        this.recoveryRate = recoveryRate;
    }

    public String getName() {
        return name;
    }

    public double getInfectionRate() {
        return infectionRate;
    }

    public double getRecoveryRate() {
        return recoveryRate;
    }

    public void setInfectionRate(double infectionRate) {
        this.infectionRate = infectionRate;
    }

    public void setRecoveryRate(double recoveryRate) {
        this.recoveryRate = recoveryRate;
    }

    @Override
    public String toString() {
        return "Disease: " + name
                + ", Infection Rate: " + infectionRate
                + ", Recovery Rate: " + recoveryRate;
    }
}
