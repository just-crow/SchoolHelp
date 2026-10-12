package com.schoolhelp.app

import com.schoolhelp.app.sharing.SharingRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SharingChecksumTest {
    @Test fun audioChecksumMatchesStandardSha256() {
        val file=File.createTempFile("shared-audio", ".bin")
        try {
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",SharingRepository.checksum(file))
        } finally { file.delete() }
    }
}
