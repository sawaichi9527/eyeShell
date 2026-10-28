package io.github.sawaichi9527.eyeshell.ssh

import org.apache.sshd.sftp.client.SftpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SftpOwnerLabelTest {
    @Test
    fun `server-provided names win`() {
        val attributes = SftpClient.Attributes().owner("eye").group("sudo")

        assertEquals("eye", ownerLabel(attributes))
        assertEquals("sudo", groupLabel(attributes))
    }

    @Test
    fun `a version 3 listing falls back to the numeric uid and gid`() {
        val attributes = SftpClient.Attributes()
            .addFlag(SftpClient.Attribute.UidGid)
            .owner(1000, 24)

        assertEquals("1000", ownerLabel(attributes))
        assertEquals("24", groupLabel(attributes))
    }

    @Test
    fun `a listing that sent neither names nor ids stays unknown`() {
        val attributes = SftpClient.Attributes()

        assertNull(ownerLabel(attributes))
        assertNull(groupLabel(attributes))
    }

    @Test
    fun `blank names do not hide the numeric fallback`() {
        val attributes = SftpClient.Attributes()
            .addFlag(SftpClient.Attribute.UidGid)
            .owner(0, 0)
        attributes.owner = " "

        assertEquals("0", ownerLabel(attributes))
    }
}
