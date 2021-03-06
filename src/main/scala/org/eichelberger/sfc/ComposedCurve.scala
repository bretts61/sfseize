package org.eichelberger.sfc

import org.eichelberger.sfc.SpaceFillingCurve._
import org.eichelberger.sfc.utils.Lexicographics.Lexicographic

object ComposedCurve {
  val EmptyQuery = Query(Seq[OrdinalRanges]())

  case class CoveringReturn(query: Query, cell: Cell) {
    def this(cell: Cell) = this(EmptyQuery, cell)
  }
}

import ComposedCurve._

// Composables can be either SFCs or Partitioners;
// leaf nodes must all be Partitioners
class ComposedCurve(val delegate: SpaceFillingCurve, val children: Seq[Composable])
  extends SpaceFillingCurve with Lexicographic {

  lazy val precisions: OrdinalVector = delegate.precisions

  lazy val numLeafNodes: Int = children.map {
    case c: ComposedCurve => c.numLeafNodes
    case d: Dimension[_]  => 1
  }.sum

  override lazy val plys: Int = 1 + children.map(_.plys).max

  lazy val name: String = delegate.name +
    children.map(_.name).mkString("(", ",", ")")

  private def _getRangesCoveringCell(cell: Cell): CoveringReturn = {
    // dimension ranges must be picked off in-order
    val covretFromChildren = children.foldLeft(new CoveringReturn(cell))((covret, child) => child match {
      case c: ComposedCurve =>
        val CoveringReturn(subQuery: Query, subCell: Cell) = c._getRangesCoveringCell(covret.cell)
        CoveringReturn(covret.query + subQuery, subCell)
      case d: Dimension[_]  =>
        val dimRange = covret.cell.dimensions.head
        val idxRange = OrdinalPair(d.indexAny(dimRange.min), d.indexAny(dimRange.max))
        val subQuery = Query(Seq(OrdinalRanges(idxRange)))
        CoveringReturn(covret.query + subQuery, Cell(covret.cell.dimensions.drop(1)))
    })

    // having all of the dimension ranges you need, use them
    val ranges = delegate.getRangesCoveringQuery(covretFromChildren.query)
    val rangesToQuery = Query(Seq(OrdinalRanges(ranges.toList:_*)))
    CoveringReturn(rangesToQuery, covretFromChildren.cell)
  }

  def getRangesCoveringCell(cell: Cell): Iterator[OrdinalPair] = {
    val CoveringReturn(queryResult: Query, emptyCell: Cell) = _getRangesCoveringCell(cell)
    require(emptyCell.size == 0, "Expected the cell to be depleted; found ${cell.size} entries remaining")
    require(queryResult.numDimensions == 1, "Expected a single return dimension; found ${queryResult.numDimensions} instead")

    // coerce types
    queryResult.rangesPerDim.head.iterator
  }

  // it doesn't really make sense to call this routine, does it?
  def getRangesCoveringQuery(query: Query): Iterator[OrdinalPair] =
    throw new UnsupportedOperationException(
      "This routine is not sensible for a composed curve.  Try getRangesCoveringCell(Cell) instead.")

  def index(point: OrdinalVector): OrdinalNumber =
    delegate.index(point)

  def inverseIndex(index: OrdinalNumber): OrdinalVector =
    delegate.inverseIndex(index)

  def pointToIndex(values: Seq[Any]): OrdinalNumber = {
    require(values.size == numLeafNodes, s"Number of values (${values.size}) must equal number of leaf-nodes ($numLeafNodes)")

    // convert these values to ordinal numbers
    val (ordinalVector: OrdinalVector, _) = children.foldLeft((OrdinalVector(), values))((acc, child) => acc match {
      case (ordsSoFar, valuesLeft) =>
        val (ord: OrdinalNumber, numRemaining: Int) = child match {
          case c: ComposedCurve =>
            val thisChildsValues = valuesLeft.take(c.numLeafNodes)
            (c.pointToIndex(thisChildsValues), c.numLeafNodes)
          case d: Dimension[_]  =>
            val thisChildsValues = valuesLeft.take(1)
            (d.indexAny(thisChildsValues.head), 1)
          case _ => throw new Exception("Unrecognized child type")
        }
        (ordsSoFar ++ ord, valuesLeft.drop(numRemaining))
    })

    // ask the delegate to do the final join
    delegate.index(ordinalVector)
  }

  def pointToHash(point: Seq[_]): String =
    lexEncodeIndex(pointToIndex(point))

  def indexToCell(index: OrdinalNumber): Cell = {
    // decompose this single index into coordinates
    val ordinalsVector = delegate.inverseIndex(index)

    // farm out these coordinates among the children
    val dims: Seq[Dimension[_]] = children.zip(ordinalsVector.toSeq).flatMap {
      case (child, ordinal) =>
        child match {
          case c: ComposedCurve => c.indexToCell(ordinal).dimensions
          case d: Dimension[_]  => Seq(d.inverseIndex(ordinal))
        }
    }

    Cell(dims)
  }

  def hashToCell(hash: String): Cell =
    indexToCell(lexDecodeIndex(hash))
}
