package contagion;

/**
 * Keeps track of the cure progress.
 *
 * @author YU-JIE CHEN
 */
public class CureSystem {

    private double progress;

    public CureSystem() {
        progress = 0;
    }

    public double getProgress() {
        return progress;
    }

    public void developCure(double amount) {
        progress += amount;

        if (progress > 100) {
            progress = 100;
        }
    }

    public boolean isComplete() {
        return progress >= 100;
    }

    public double applyCure(Disease disease) {
        if (!isComplete()) {
            return disease.getInfectionRate();
        }

        return disease.getInfectionRate() * 0.5;
    }

    @Override
    public String toString() {
        return "Cure Progress: " + progress + "%";
    }
}
