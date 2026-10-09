package org.southtyrol.transit

import org.junit.Assert.assertEquals
import org.junit.Test
import org.southtyrol.transit.feature.settings.licenseText

class LicenseTextTest {
    @Test fun rejoinsWrappedParagraphsButKeepsListsAndBlankLines() {
        val wrapped = "Redistribution and use in source and binary forms, with or without\nmodification, are permitted:\n\n* first condition\n  continues here\n* second"
        assertEquals(
            "Redistribution and use in source and binary forms, with or without modification, are permitted:\n\n* first condition continues here\n* second",
            licenseText(wrapped),
        )
    }

    @Test fun fillsSpdxPlaceholdersWithTheirDefaultText() {
        val template = "Copyright (c) <<var;name=copyright;original=<year> <owner>;match=.+>> All rights reserved.<<beginOptional>> extra<<endOptional>>"
        assertEquals("Copyright (c) <year> <owner> All rights reserved. extra", licenseText(template))
    }

    @Test fun indentedBulletsStayOnTheirOwnLines() {
        val protobufStyle = "conditions are\nmet:\n\n    * Redistributions of source code must retain the above\ncopyright notice.\n    * Redistributions in binary form"
        assertEquals(
            "conditions are met:\n\n    * Redistributions of source code must retain the above copyright notice.\n    * Redistributions in binary form",
            licenseText(protobufStyle),
        )
    }
}
