package com.twitter.finatra.json.internal.serde

import __foursquare_shaded__.com.fasterxml.jackson.core.util.DefaultIndenter
import __foursquare_shaded__.com.fasterxml.jackson.core.util.DefaultPrettyPrinter

private[finatra] object ArrayElementsOnNewLinesPrettyPrinter extends DefaultPrettyPrinter {
  _arrayIndenter = DefaultIndenter.SYSTEM_LINEFEED_INSTANCE
}
