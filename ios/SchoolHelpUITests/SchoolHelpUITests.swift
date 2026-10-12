import XCTest

final class SchoolHelpUITests:XCTestCase {
    func testHomeAndSettingsNavigation() {
        let app=XCUIApplication();app.launch()
        XCTAssertTrue(app.staticTexts["SchoolHelp"].exists)
        app.buttons["Settings"].tap()
        XCTAssertTrue(app.staticTexts["Settings"].exists)
        app.buttons["Done"].tap()
        XCTAssertTrue(app.staticTexts["Shared listening"].exists)
    }
}
