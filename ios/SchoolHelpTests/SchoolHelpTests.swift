import XCTest
@testable import SchoolHelp

@MainActor final class SchoolHelpTests:XCTestCase {
    func testOrderedSpokenAnswerAndRewriteDetection() throws {
        let answer=Solution(problems:[
            Problem(number:"1",question:"",answer:"one half",explanation:"",spokenText:"Write one half. Put one above and two below the fraction bar.",unreadable:false,page:1),
            Problem(number:"2",question:"",answer:"present",explanation:"",spokenText:"In the Plant cell row, under Cell wall, write Present.",unreadable:false,page:2)
        ])
        XCTAssertFalse(ListeningText.needsRewrite(answer))
        XCTAssertTrue(answer.speech(language:"en",explanations:false).hasPrefix("Page 1. Question 1."))
        XCTAssertTrue(answer.speech(language:"en",explanations:false).contains("Page 2. Question 2."))
    }
    func testVisualFractionNeedsRewrite() {
        let answer=Solution(problems:[Problem(number:"1",question:"",answer:"x",explanation:"",spokenText:"1/2",unreadable:false,page:1)])
        XCTAssertTrue(ListeningText.needsRewrite(answer))
    }
    func testConsumingOneItemPreservesNext() throws {
        let sharing=Sharing()
        for index in 1...2 {
            let id="fixture-\(index)"
            let item=ReceivedItem(id:id,sequence:Int64(index),solution:Solution(problems:[]),spoken_text:"",checksum:"",url:"",expires_at:"",speed:1)
            sharing.store.put(id,"incoming",Int64(index),try JSONEncoder().encode(item))
            try Data([1]).write(to:sharing.audio(id))
        }
        XCTAssertEqual(sharing.current()?.id,"fixture-1")
        sharing.consume("fixture-1")
        XCTAssertEqual(sharing.current()?.id,"fixture-2")
        sharing.consume("fixture-1")
        XCTAssertEqual(sharing.current()?.id,"fixture-2")
        for id in ["fixture-1","fixture-2"] {sharing.store.delete(id);try? FileManager.default.removeItem(at:sharing.audio(id))}
    }
}
