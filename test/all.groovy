import org.junit.internal.TextListener
import org.junit.runner.JUnitCore

def junit = new JUnitCore()
junit.addListener(new TextListener(System.out))
def result = junit.run(
    MessageTests.class,
    UtilsTests.class,
    EndToEndTests.class,
    HvacV3WizardTests.class,
    HvacCommandStorageTests.class
)
System.exit(result.wasSuccessful() ? 0 : 1)