import org.junit.Test

/**
 * Debug scratch tests against the v2 wizard snapshot (app.v2.groovy).
 * v3 has no equivalent isCacheValid/matchCodeToModel surface.
 */
class DebugAppTest {

    @Test
    void testSimpleMethodCall() {
        def app = new HubitatAppFacade("app.v2.groovy")

        // Test that we can call isCacheValid
        def result1 = app.isCacheValid()
        println "isCacheValid() with no cache: ${result1}"
        assert result1 == false

        // Set cache and test again
        app.state.smartirCache = [
            lastFetched: System.currentTimeMillis()
        ]
        def result2 = app.isCacheValid()
        println "isCacheValid() with fresh cache: ${result2}"
        assert result2 == true
    }

    @Test
    void testMatchCodeToModelSimple() {
        def app = new HubitatAppFacade("app.v2.groovy")

        println "Initial state: ${app.state}"

        // Set up simple cache
        app.state.smartirCache = [
            lastFetched: System.currentTimeMillis(),
            manufacturers: [
                "TestBrand": [
                    "1234": [
                        manufacturer: "TestBrand",
                        commands: [
                            off: "TEST_CODE"
                        ]
                    ]
                ]
            ]
        ]

        println "After setting cache: ${app.state.smartirCache}"

        // Try to call matchCodeToModel
        def result = app.matchCodeToModel("TEST_CODE", "TestBrand")
        println "Result: ${result}"

        if (result == null) {
            println "matchCodeToModel returned null"
            println "Log output: ${app.log.getOutput()}"
        } else {
            println "Result smartIrId: ${result.smartIrId}"
        }
    }
}
