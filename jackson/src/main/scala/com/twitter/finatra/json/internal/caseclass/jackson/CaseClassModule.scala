package com.twitter.finatra.json.internal.caseclass.jackson

import __foursquare_shaded__.com.fasterxml.jackson.module.scala._

private[finatra] object CaseClassModule extends JacksonModule {
  override def getModuleName: String = getClass.getName

  this += { _.addDeserializers(new CaseClassDeserializers()) }
}
