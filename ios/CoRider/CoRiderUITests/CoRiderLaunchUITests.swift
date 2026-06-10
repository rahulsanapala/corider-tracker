import XCTest

final class CoRiderLaunchUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testAppLaunchesToForeground() throws {
        let app = XCUIApplication()
        app.launch()

        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15))
        handleSystemAlerts()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 5))
        XCTAssertTrue(app.tabBars.buttons["Map"].waitForExistence(timeout: 10))

        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "CoRider launch screen"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func handleSystemAlerts() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

        let allowButton = springboard.buttons["Allow"]
        if allowButton.waitForExistence(timeout: 5) {
            allowButton.tap()
        }

        let whileUsingButton = springboard.buttons["Allow While Using App"]
        if whileUsingButton.waitForExistence(timeout: 2) {
            whileUsingButton.tap()
        }
    }
}
