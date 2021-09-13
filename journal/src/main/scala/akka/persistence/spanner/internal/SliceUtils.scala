/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.spanner.internal

import akka.annotation.InternalApi
import akka.persistence.typed.PersistenceId
import scala.collection.immutable

/**
 * INTERNAL API
 */
@InternalApi
private[spanner] object SliceUtils {
  def sliceForPersistenceId(persistenceId: String, maxNumberOfSlices: Int): Int =
    math.abs(persistenceId.hashCode % maxNumberOfSlices)

  def extractEntityTypeHintFromPersistenceId(persistenceId: String): String = {
    val i = persistenceId.indexOf(PersistenceId.DefaultSeparator) // TODO configurable separator
    if (i == -1) ""
    else persistenceId.substring(0, i)
  }

  def sliceRanges(numberOfRanges: Int, maxNumberOfSlices: Int): immutable.Seq[Range] = {
    val rangeSize = maxNumberOfSlices / numberOfRanges
    require(
      numberOfRanges * rangeSize == maxNumberOfSlices,
      s"numberOfRanges [$numberOfRanges] must be a whole number divisor of maxNumberOfSlices [$maxNumberOfSlices]."
    )
    (0 until numberOfRanges).map { i =>
      (i * rangeSize until i * rangeSize + rangeSize)
    }.toVector
  }
}
