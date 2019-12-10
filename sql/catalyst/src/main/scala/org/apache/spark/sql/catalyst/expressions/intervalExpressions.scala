/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.expressions

import java.util.Locale

import scala.util.control.NonFatal

import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode}
import org.apache.spark.sql.catalyst.util.IntervalUtils
import org.apache.spark.sql.catalyst.util.IntervalUtils._
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.CalendarInterval

abstract class ExtractIntervalPart(
    child: Expression,
    val dataType: DataType,
    func: CalendarInterval => Any,
    funcName: String)
  extends UnaryExpression with ExpectsInputTypes with Serializable {

  override def inputTypes: Seq[AbstractDataType] = Seq(CalendarIntervalType)

  override protected def nullSafeEval(interval: Any): Any = {
    func(interval.asInstanceOf[CalendarInterval])
  }

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val iu = IntervalUtils.getClass.getName.stripSuffix("$")
    defineCodeGen(ctx, ev, c => s"$iu.$funcName($c)")
  }
}

case class ExtractIntervalMillenniums(child: Expression)
  extends ExtractIntervalPart(child, IntegerType, getMillenniums, "getMillenniums")

case class ExtractIntervalCenturies(child: Expression)
  extends ExtractIntervalPart(child, IntegerType, getCenturies, "getCenturies")

case class ExtractIntervalDecades(child: Expression)
  extends ExtractIntervalPart(child, IntegerType, getDecades, "getDecades")

case class ExtractIntervalYears(child: Expression)
  extends ExtractIntervalPart(child, IntegerType, getYears, "getYears")

case class ExtractIntervalQuarters(child: Expression)
  extends ExtractIntervalPart(child, ByteType, getQuarters, "getQuarters")

case class ExtractIntervalMonths(child: Expression)
  extends ExtractIntervalPart(child, ByteType, getMonths, "getMonths")

case class ExtractIntervalDays(child: Expression)
  extends ExtractIntervalPart(child, IntegerType, getDays, "getDays")

case class ExtractIntervalHours(child: Expression)
  extends ExtractIntervalPart(child, LongType, getHours, "getHours")

case class ExtractIntervalMinutes(child: Expression)
  extends ExtractIntervalPart(child, ByteType, getMinutes, "getMinutes")

case class ExtractIntervalSeconds(child: Expression)
  extends ExtractIntervalPart(child, DecimalType(8, 6), getSeconds, "getSeconds")

case class ExtractIntervalMilliseconds(child: Expression)
  extends ExtractIntervalPart(child, DecimalType(8, 3), getMilliseconds, "getMilliseconds")

case class ExtractIntervalMicroseconds(child: Expression)
  extends ExtractIntervalPart(child, LongType, getMicroseconds, "getMicroseconds")

// Number of seconds in 10000 years is 315576000001 (30 days per one month)
// which is 12 digits + 6 digits for the fractional part of seconds.
case class ExtractIntervalEpoch(child: Expression)
  extends ExtractIntervalPart(child, DecimalType(18, 6), getEpoch, "getEpoch")

object ExtractIntervalPart {

  def parseExtractField(
      extractField: String,
      source: Expression,
      errorHandleFunc: => Nothing): Expression = extractField.toUpperCase(Locale.ROOT) match {
    case "MILLENNIUM" | "MILLENNIA" | "MIL" | "MILS" => ExtractIntervalMillenniums(source)
    case "CENTURY" | "CENTURIES" | "C" | "CENT" => ExtractIntervalCenturies(source)
    case "DECADE" | "DECADES" | "DEC" | "DECS" => ExtractIntervalDecades(source)
    case "YEAR" | "Y" | "YEARS" | "YR" | "YRS" => ExtractIntervalYears(source)
    case "QUARTER" | "QTR" => ExtractIntervalQuarters(source)
    case "MONTH" | "MON" | "MONS" | "MONTHS" => ExtractIntervalMonths(source)
    case "DAY" | "D" | "DAYS" => ExtractIntervalDays(source)
    case "HOUR" | "H" | "HOURS" | "HR" | "HRS" => ExtractIntervalHours(source)
    case "MINUTE" | "M" | "MIN" | "MINS" | "MINUTES" => ExtractIntervalMinutes(source)
    case "SECOND" | "S" | "SEC" | "SECONDS" | "SECS" => ExtractIntervalSeconds(source)
    case "MILLISECONDS" | "MSEC" | "MSECS" | "MILLISECON" | "MSECONDS" | "MS" =>
      ExtractIntervalMilliseconds(source)
    case "MICROSECONDS" | "USEC" | "USECS" | "USECONDS" | "MICROSECON" | "US" =>
      ExtractIntervalMicroseconds(source)
    case "EPOCH" => ExtractIntervalEpoch(source)
    case _ => errorHandleFunc
  }
}

abstract class IntervalNumOperation(
    interval: Expression,
    num: Expression,
    operation: (CalendarInterval, Double) => CalendarInterval,
    operationName: String)
  extends BinaryExpression with ImplicitCastInputTypes with Serializable {
  override def left: Expression = interval
  override def right: Expression = num

  override def inputTypes: Seq[AbstractDataType] = Seq(CalendarIntervalType, DoubleType)
  override def dataType: DataType = CalendarIntervalType

  override def nullable: Boolean = true

  override def nullSafeEval(interval: Any, num: Any): Any = {
    try {
      operation(interval.asInstanceOf[CalendarInterval], num.asInstanceOf[Double])
    } catch {
      case _: java.lang.ArithmeticException => null
    }
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    nullSafeCodeGen(ctx, ev, (interval, num) => {
      val iu = IntervalUtils.getClass.getName.stripSuffix("$")
      s"""
        try {
          ${ev.value} = $iu.$operationName($interval, $num);
        } catch (java.lang.ArithmeticException e) {
          ${ev.isNull} = true;
        }
      """
    })
  }

  override def prettyName: String = operationName + "_interval"
}

case class MultiplyInterval(interval: Expression, num: Expression)
  extends IntervalNumOperation(interval, num, multiply, "multiply")

case class DivideInterval(interval: Expression, num: Expression)
  extends IntervalNumOperation(interval, num, divide, "divide")

// scalastyle:off line.size.limit
@ExpressionDescription(
  usage = "_FUNC_(years, months, weeks, days, hours, mins, secs) - Make interval from years, months, weeks, days, hours, mins and secs.",
  arguments = """
    Arguments:
      * years - the number of years, positive or negative
      * months - the number of months, positive or negative
      * weeks - the number of weeks, positive or negative
      * days - the number of days, positive or negative
      * hours - the number of hours, positive or negative
      * mins - the number of minutes, positive or negative
      * secs - the number of seconds with the fractional part in microsecond precision.
  """,
  examples = """
    Examples:
      > SELECT _FUNC_(100, 11, 1, 1, 12, 30, 01.001001);
       100 years 11 months 8 days 12 hours 30 minutes 1.001001 seconds
      > SELECT _FUNC_(100, null, 3);
       NULL
  """,
  since = "3.0.0")
// scalastyle:on line.size.limit
case class MakeInterval(
    years: Expression,
    months: Expression,
    weeks: Expression,
    days: Expression,
    hours: Expression,
    mins: Expression,
    secs: Expression)
  extends SeptenaryExpression with ImplicitCastInputTypes {

  def this(
      years: Expression,
      months: Expression,
      weeks: Expression,
      days: Expression,
      hours: Expression,
      mins: Expression) = {
    this(years, months, weeks, days, hours, mins, Literal(Decimal(0, 8, 6)))
  }
  def this(
      years: Expression,
      months: Expression,
      weeks: Expression,
      days: Expression,
      hours: Expression) = {
    this(years, months, weeks, days, hours, Literal(0))
  }
  def this(years: Expression, months: Expression, weeks: Expression, days: Expression) =
    this(years, months, weeks, days, Literal(0))
  def this(years: Expression, months: Expression, weeks: Expression) =
    this(years, months, weeks, Literal(0))
  def this(years: Expression, months: Expression) = this(years, months, Literal(0))
  def this(years: Expression) = this(years, Literal(0))
  def this() = this(Literal(0))

  override def children: Seq[Expression] = Seq(years, months, weeks, days, hours, mins, secs)
  // Accept `secs` as DecimalType to avoid loosing precision of microseconds while converting
  // them to the fractional part of `secs`.
  override def inputTypes: Seq[AbstractDataType] = Seq(IntegerType, IntegerType, IntegerType,
    IntegerType, IntegerType, IntegerType, DecimalType(8, 6))
  override def dataType: DataType = CalendarIntervalType
  override def nullable: Boolean = true

  override def nullSafeEval(
      year: Any,
      month: Any,
      week: Any,
      day: Any,
      hour: Any,
      min: Any,
      sec: Option[Any]): Any = {
    try {
      IntervalUtils.makeInterval(
        year.asInstanceOf[Int],
        month.asInstanceOf[Int],
        week.asInstanceOf[Int],
        day.asInstanceOf[Int],
        hour.asInstanceOf[Int],
        min.asInstanceOf[Int],
        sec.map(_.asInstanceOf[Decimal]).getOrElse(Decimal(0, 8, 6)))
    } catch {
      case _: ArithmeticException => null
    }
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    nullSafeCodeGen(ctx, ev, (year, month, week, day, hour, min, sec) => {
      val iu = IntervalUtils.getClass.getName.stripSuffix("$")
      val secFrac = sec.getOrElse("0")
      s"""
        try {
          ${ev.value} = $iu.makeInterval($year, $month, $week, $day, $hour, $min, $secFrac);
        } catch (java.lang.ArithmeticException e) {
          ${ev.isNull} = true;
        }
      """
    })
  }

  override def prettyName: String = "make_interval"
}

abstract class IntervalJustifyLike(
    child: Expression,
    justify: CalendarInterval => CalendarInterval,
    justifyFuncName: String) extends UnaryExpression with ExpectsInputTypes {
  override def inputTypes: Seq[AbstractDataType] = Seq(CalendarIntervalType)

  override def dataType: DataType = CalendarIntervalType

  override def nullSafeEval(input: Any): Any = {
    try {
      justify(input.asInstanceOf[CalendarInterval])
    } catch {
      case NonFatal(_) => null
    }
  }

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    nullSafeCodeGen(ctx, ev, child => {
      val iu = IntervalUtils.getClass.getCanonicalName.stripSuffix("$")
      s"""
         |try {
         |  ${ev.value} = $iu.$justifyFuncName($child);
         |} catch (java.lang.ArithmeticException e) {
         |  ${ev.isNull} = true;
         |}
         |""".stripMargin
    })
  }

  override def prettyName: String = justifyFuncName
}

@ExpressionDescription(
  usage = "_FUNC_(expr) - Adjust interval so 30-day time periods are represented as months",
  examples = """
    Examples:
      > SELECT _FUNC_(interval '1 month -59 day 25 hour');
       -29 days 25 hours
  """,
  since = "3.0.0")
case class JustifyDays(child: Expression)
  extends IntervalJustifyLike(child, justifyDays, "justifyDays")

@ExpressionDescription(
  usage = "_FUNC_(expr) - Adjust interval so 24-hour time periods are represented as days",
  examples = """
    Examples:
      > SELECT _FUNC_(interval '1 month -59 day 25 hour');
       1 months -57 days -23 hours
  """,
  since = "3.0.0")
case class JustifyHours(child: Expression)
  extends IntervalJustifyLike(child, justifyHours, "justifyHours")

@ExpressionDescription(
  usage = "_FUNC_(expr) - Adjust interval using justifyHours and justifyDays, with additional" +
    " sign adjustments",
  examples = """
    Examples:
      > SELECT _FUNC_(interval '1 month -59 day 25 hour');
       -27 days -23 hours
  """,
  since = "3.0.0")
case class JustifyInterval(child: Expression)
  extends IntervalJustifyLike(child, justifyInterval, "justifyInterval")
