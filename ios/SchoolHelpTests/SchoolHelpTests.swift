import XCTest
@testable import SchoolHelp

@MainActor final class SchoolHelpTests:XCTestCase {
    func testOrderedSpokenAnswerAndRewriteDetection() throws {
        let answer=Solution(problems:[
            Problem(number:"1",question:"",answer:"one half",explanation:"",spokenText:"Write one half. Put one above and two below the fraction bar.",unreadable:false,page:1),
            Problem(number:"2",question:"",answer:"present",explanation:"",spokenText:"In the Plant cell row, under Cell wall, write Present.",unreadable:false,page:2)
        ])
        XCTAssertFalse(ListeningText.needsRewrite(answer))
        XCTAssertTrue(answer.speech(language:"en",explanations:false).hasPrefix("1."))
        XCTAssertTrue(answer.speech(language:"en",explanations:false).contains("2. In the Plant cell"))
    }
    func testLocalizedLabelOverridesAppLanguage() throws {
        let answer=try ListeningText.parse(#"{"problems":[{"number":"1","question":"Co to jest komórka?","answer":"Komórka.","explanation":"","spokenText":"Komórka jest podstawową jednostką życia.","spokenLabel":"Strona pierwsza. Zadanie pierwsze.","unreadable":false,"page":1}]}"#)
        XCTAssertEqual(answer.speech(language:"en",explanations:false),"Strona pierwsza. Zadanie pierwsze. Komórka jest podstawową jednostką życia.")
    }
    func testTypedLanguageIsUsedAndSaved() throws {
        var settings=AISettings()
        settings.outputLanguage="French"
        let restored=try JSONDecoder().decode(AISettings.self,from:JSONEncoder().encode(settings))
        XCTAssertEqual(restored.requestedLanguage,"French")
        let prompt=ListeningText.prompt(language:restored.requestedLanguage,explanations:false)
        XCTAssertTrue(prompt.contains("chosen the output language: French"))
        XCTAssertTrue(prompt.contains("Do not detect or choose an output language from the photos"))
    }
    func testWritingTimeUsesCopiedWordsAndFixedOverrides() throws {
        XCTAssertEqual(WritingPauses.seconds("one two three",30,nil),6)
        XCTAssertEqual(WritingPauses.seconds("words",30,0),0)
        XCTAssertEqual(WritingPauses.seconds("words",30,12.5),12.5)
        let solution=try ListeningText.parse(#"{"problems":[{"number":"1","question":"","answer":"A B","explanation":"","spokenText":"Write A then B.","spokenLabel":"Question one.","unreadable":false,"speechSegments":[{"text":"Write A.","writingText":"A","pauseSeconds":null},{"text":"Write B.","writingText":"B","pauseSeconds":9}]}]}"#)
        let plan=try WritingPauses.plan(solution,30,false)
        XCTAssertEqual(plan.map{$0.text},["Question one. Write A.","Write B."])
        XCTAssertEqual(plan.map{$0.pauseSeconds},[2,9])
    }
    func testEssayInstructionsAndParagraphsRemainCompleteWithoutOptionalReasoning() {
        let prompt=ListeningText.prompt(language:"English",explanations:false,writingWpm:30)
        XCTAssertTrue(prompt.contains("Distinguish the task genre"))
        XCTAssertTrue(prompt.contains("Preserve them even when optional explanations are disabled"))
        let essay="A thesis introduces the argument.\n\nThe body develops evidence.\n\nThe conclusion answers the task."
        let problem=Problem(number:"1",question:"",answer:essay,explanation:"",spokenText:essay,unreadable:false,spokenLabel:"Essay.",page:nil)
        XCTAssertEqual(Solution(problems:[problem]).speech(language:"en",explanations:false),"Essay. \(essay)")
    }
    func testWritingCueFilePreservesPauseDurationAtQuarterSpeed() throws {
        let file=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString+".wav")
        defer {try? FileManager.default.removeItem(at:file)}
        try WritingCue.write(file,6,0.25)
        let data=try Data(contentsOf:file)
        XCTAssertEqual(data.count,44+12000*2)
        XCTAssertEqual(String(data:data.prefix(4),encoding:.utf8),"RIFF")
        XCTAssertTrue(data.dropFirst(44).contains(where:{$0 != 0}))
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
