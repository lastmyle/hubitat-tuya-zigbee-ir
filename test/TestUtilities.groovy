/**
 * Shared test utilities for HVAC tests
 * Contains common mock classes used across multiple test files
 */

/**
 * Mock event for testing event handlers
 */
class MockEvent {
    def device
    String name = "lastLearnedCode"
    def value
    Date date = new Date()

    String toString() {
        return "[${name}: ${value}]"
    }
}

/**
 * StringLog for capturing log output in tests
 */
class StringLog {
    StringWriter out = new StringWriter()

    def error(String message) {
        out.write("[error] ${message}\n")
    }

    def warn(String message) {
        out.write("[warn] ${message}\n")
    }

    def info(String message) {
        out.write("[info] ${message}\n")
    }

    def debug(String message) {
        out.write("[debug] ${message}\n")
    }

    String getOutput() {
        return out.toString()
    }

    void clear() {
        out = new StringWriter()
    }
}
