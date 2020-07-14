package tscfg

import com.typesafe.config._
import tscfg.generators.tsConfigUtil
import tscfg.DefineCase._
import tscfg.Struct.{MemberStruct, SharedObjectStruct}
import tscfg.exceptions.ObjectDefinitionException
import tscfg.model.durations.ms
import tscfg.model.{AbstractObjectType, AnnType, DURATION, EnumObjectType, ListType, ObjectType, SIZE}

import scala.jdk.CollectionConverters._

case class ModelBuildResult(objectType: model.ObjectType,
                            warnings: List[buildWarnings.Warning])

object buildWarnings {

  sealed abstract class Warning(ln: Int,
                                src: String,
                                msg: String = "") {
    val line: Int = ln
    val source: String = src
    val message: String = msg
  }

  case class MultElemListWarning(ln: Int, src: String) extends
    Warning(ln, src, "only first element will be considered")

  case class OptListElemWarning(ln: Int, src: String) extends
    Warning(ln, src, "ignoring optional mark in list's element type")

  case class DefaultListElemWarning(ln: Int, default: String, elemType: String) extends
    Warning(ln, default, s"ignoring default value='$default' in list's element type: $elemType")

}

class ModelBuilder(assumeAllRequired: Boolean = false) {

  import collection._
  import buildWarnings._

  def build(conf: Config): ModelBuildResult = {
    warns.clear()
    ModelBuildResult(
      objectType = fromConfig(Namespace.root, conf),
      warnings = warns.toList.sortBy(_.line))
  }

  private val warns = collection.mutable.ArrayBuffer[Warning]()

  /**
    * Adds additional information to the object struct provided by prepended comment strings
    *
    * @param conf   [[Config]] with basic information
    * @param struct "Basic" object struct to be enriched
    * @throws ObjectDefinitionException If for example the additional comment string is malformed
    * @return An extended [[SharedObjectStruct]] if additional information s available, the untouched [[MemberStruct]]
    */
  @throws[ObjectDefinitionException]
  private def enrichWithAnnotationInformation(conf: Config)(struct: Struct): Struct = {
    /* Get all comment lines, which denote a shared object definition */
    val defineLines = conf.getValue(struct.name).origin().comments().asScala.map(_.trim).filter(_.startsWith("@define"))
    defineLines.length match {
      case 0 =>
        /* The given member struct is no shared object definition: Return the struct as it is */
        struct
      case 1 =>
        /* This is a shared object definition: Extract the information and merge them into the basic member struct */
        val additionalComments = defineLines.headOption match {
          case Some(comment) => comment
          case None => throw ObjectDefinitionException("Cannot get first comment line, although exactly one was found.")
        }

        try {
          val defineCase = DefineCase.getDefineCase(additionalComments) match {
            case Some(value) => value
            case None =>
              throw ObjectDefinitionException(s"Unable to extract additional information from comment '$additionalComments'")
          }
          struct match {
            case MemberStruct(name, members) => SharedObjectStruct(name, members, defineCase)
          }
        } catch {
          case e: RuntimeException =>
            throw ObjectDefinitionException(s"Malformed additional information in comment '@define $additionalComments'", e)
        }
      case _ => throw ObjectDefinitionException(s"multiple @define's for ${struct.name}.")
    }
  }

  private def fromConfig(namespace: Namespace, conf: Config): model.ObjectType = {
    /* Get all structs with basic information from config */
    val memberStructs: List[Struct] = getMemberStructs(conf)

    // Add some information to the structs that are shared objects and ensure, that `@define`s are traversed first
    // (TODO some future general revision as this is becoming rather ad hoc)
    // FIXME(#67) use any `@define` interdependency info captured above for the ordering below
    val enrichedStructs = memberStructs.map(enrichWithAnnotationInformation(conf)).sortWith {
      case (_: SharedObjectStruct, _) => true
      case (_, _) => false
    }

    val members: immutable.Map[String, model.AnnType] = enrichedStructs.map { childStruct =>
      val name = childStruct.name
      val cv = conf.getValue(name)

      val (childType, optional, default) = {
        if (childStruct.isLeaf) {
          val valueString = util.escapeValue(cv.unwrapped().toString)

          val isEnum = childStruct match {
            case SharedObjectStruct(_, _, defineCase) => defineCase.isInstanceOf[EnumDefineCase]
            case _ => false
          }

          getTypeFromConfigValue(namespace, cv, isEnum) match {
            case typ: model.STRING.type =>
              namespace.resolveDefine(valueString) match {
                case Some(ort) =>
                  (ort, false, None)

                case None =>
                  toAnnBasicType(valueString) match {
                    case Some(annBasicType) =>
                      annBasicType

                    case None =>
                      (typ, true, Some(valueString))
                  }
              }

            case typ: model.BasicType =>
              (typ, true, Some(valueString))

            case typ =>
              (typ, false, None)
          }
        }
        else {
          (fromConfig(namespace.extend(name), conf.getConfig(name)), false, None)
        }
      }

      val comments = cv.origin().comments().asScala.toList
      val optFromComments = comments.exists(_.trim.startsWith("@optional"))
      val commentsOpt = if (comments.isEmpty) None else Some(comments.mkString("\n"))

      // per Lightbend Config restrictions involving $, leave the key alone if
      // contains $, otherwise unquote the key in case is quoted.
      val adjName = if (name.contains("$")) name else name.replaceAll("^\"|\"$", "")

      // effective optional and default:
      val (effOptional, effDefault) = if (assumeAllRequired)
        (false, None) // that is, all strictly required.
      // A possible variation: allow the `@optional` annotation to still take effect:
      //(optFromComments, if (optFromComments) default else None)
      else
      (optional || optFromComments, default)

      //println(s"ModelBuilder: effOptional=$effOptional  effDefault=$effDefault " +
      //  s"assumeAllRequired=$assumeAllRequired optFromComments=$optFromComments " +
      //  s"adjName=$adjName")

      /* get the parent class members, if any*/
      val parentClassMembers = this.parentClassMembers(commentsOpt, namespace)

      /* build the annType  */
      val annType = buildAnnType(childType, effOptional, effDefault, commentsOpt, parentClassMembers)

      annType.defineCase foreach { define =>
        namespace.addDefine(name, annType.t, define.isParent)
      }

      adjName -> annType

    }.toMap

    /* filter abstract members from root object as they don't require an instantiation */
    model.ObjectType(members.filterNot(fullPathWithObj =>
      fullPathWithObj._2.default.exists(namespace.isAbstractClassDefine)))
  }

  private def buildAnnType(childType: model.Type, effOptional: Boolean, effDefault: Option[String],
                           commentsOpt: Option[String],
                           parentClassMembers: Option[Predef.Map[String, model.AnnType]]): AnnType = {

    // if this class is a parent class (abstract class or interface) this is indicated by the childType object
    // that is passed into the AnnType instance that is returned
    val updatedChildType = childType match {
      case objType: ObjectType =>
        if (commentsOpt.exists(AnnType.isParent))
          AbstractObjectType(objType.members) else objType

      case listType: ListType =>
        if (commentsOpt.exists(AnnType.isEnum)) {
          println(s"\nWARNING: incomplete enumeration handling. Do not use `@define enum` yet\n")
          // TODO get given list to reflect it in the EnumObjectType:
          EnumObjectType(List("TODO", "actual", "members"))
        }
        else listType

      case other => other
    }

    model.AnnType(
      updatedChildType,
      optional = effOptional,
      default = effDefault,
      comments = commentsOpt,
      parentClassMembers = parentClassMembers
    )
  }

  private def parentClassMembers(commentsOpt: Option[String], namespace: Namespace):
  Option[Predef.Map[String, model.AnnType]] = {

    commentsOpt.flatMap(getDefineCase).flatMap {
      case ExtendsDefineCase(parentName) =>
        // sanity check to see if this class is defined as parent class
          namespace.getAbstractDefine(parentName).map(_.members) match {
            case Some(parentMembers) =>
              // valid parent name
              Some(parentMembers)
            case None =>
              // parent class might be defined, but not as parent -> no processing
              throw new IllegalArgumentException(
                s"'${commentsOpt.get}' is invalid because $parentName is not abstract!" +
                  s" If you want to make $parentName extendable use '@define abstract'.")
          }

      case _ => None
    }
  }

  private def getMemberStructs(conf: Config): List[Struct] = {
    val structs = mutable.HashMap[String, MemberStruct]("" -> MemberStruct(""))

    def resolve(key: String): MemberStruct = {
      if (!structs.contains(key)) structs.put(key, MemberStruct(getSimple(key)))
      structs(key)
    }

    conf.entrySet().asScala foreach { e =>
      val path = e.getKey
      val leaf = MemberStruct(path)
      doAncestorsOf(path, leaf)

      def doAncestorsOf(childKey: String, childStruct: MemberStruct): Unit = {
        val (parent, simple) = (getParent(childKey), getSimple(childKey))
        createParent(parent, simple, childStruct)

        @annotation.tailrec
        def createParent(parentKey: String, simple: String, child: MemberStruct): Unit = {
          val parentGroup = resolve(parentKey)
          parentGroup.members.put(simple, child)
          if (parentKey != "") {
            createParent(getParent(parentKey), getSimple(parentKey), parentGroup)
          }
        }
      }
    }

    def getParent(path: String): String = {
      val idx = path.lastIndexOf('.')
      if (idx >= 0) path.substring(0, idx) else ""
    }

    def getSimple(path: String): String = {
      val idx = path.lastIndexOf('.')
      if (idx >= 0) path.substring(idx + 1) else path
    }

    structs("").members.values.toList
  }

  private def getTypeFromConfigValue(namespace: Namespace, cv: ConfigValue, isEnum: Boolean): model.Type = {
    import ConfigValueType._
    cv.valueType() match {
      case STRING => model.STRING

      case BOOLEAN => model.BOOLEAN

      case NUMBER => numberType(cv.unwrapped().toString)

      case LIST if isEnum => enumType(cv.asInstanceOf[ConfigList])

      case LIST => listType(namespace, cv.asInstanceOf[ConfigList])

      case OBJECT => objType(namespace, cv.asInstanceOf[ConfigObject])

      case NULL => throw new AssertionError("null unexpected")
    }
  }

  private def toAnnBasicType(valueString: String):
  Option[(model.BasicType, Boolean, Option[String])] = {

    if (tsConfigUtil.isDurationValue(valueString))
      return Some((DURATION(ms), true, Some(valueString)))

    if (tsConfigUtil.isSizeValue(valueString))
      return Some((SIZE, true, Some(valueString)))

    val tokens = valueString.split("""\s*\|\s*""")
    val typePart = tokens(0).toLowerCase
    val hasDefault = tokens.size == 2
    val defaultValue = if (hasDefault) Some(tokens(1)) else None

    val (baseString, isOpt) = if (typePart.endsWith("?"))
      (typePart.substring(0, typePart.length - 1), true)
    else
      (typePart, hasDefault)

    val (base, qualification) = {
      val parts = baseString.split("""\s*:\s*""", 2)
      if (parts.length == 1)
        (parts(0), None)
      else
        (parts(0), Some(parts(1)))
    }

    model.recognizedAtomic.get(base) map { bt =>
      val basicType = bt match {
        case DURATION(_) if qualification.isDefined =>
          DURATION(tsConfigUtil.unifyDuration(qualification.get))
        case _ => bt
      }
      (basicType, isOpt, defaultValue)
    }
  }

  private def listType(namespace: Namespace, cv: ConfigList): model.ListType = {
    if (cv.isEmpty) throw new IllegalArgumentException("list with one element expected")

    if (cv.size() > 1) {
      val line = cv.origin().lineNumber()
      val options: ConfigRenderOptions = ConfigRenderOptions.defaults
        .setFormatted(false).setComments(false).setOriginComments(false)
      warns += MultElemListWarning(line, cv.render(options))
    }

    val cv0: ConfigValue = cv.get(0)
    val valueString = util.escapeValue(cv0.unwrapped().toString)
    val typ = getTypeFromConfigValue(namespace, cv0, isEnum = false)

    val elemType = {
      if (typ == model.STRING) {

        namespace.resolveDefine(valueString) match {
          case Some(ort) =>
            ort

          case None =>
            // see possible type from the string literal:
            toAnnBasicType(valueString) match {
              case Some((basicType, isOpt, defaultValue)) =>
                if (isOpt)
                  warns += OptListElemWarning(cv0.origin().lineNumber(), valueString)

                if (defaultValue.isDefined)
                  warns += DefaultListElemWarning(cv0.origin().lineNumber(), defaultValue.get, valueString)

                basicType

              case None =>
                typ
            }
        }
      }
      else typ
    }

    model.ListType(elemType)
  }

  private def enumType(cv: ConfigList): model.EnumObjectType = {
    if (cv.isEmpty) throw new IllegalArgumentException("enumeration with at least one element expected")

    model.EnumObjectType(cv.iterator().asScala.map(_.unwrapped().toString).toList)
  }

  private def objType(namespace: Namespace, cv: ConfigObject): model.ObjectType =
    fromConfig(namespace, cv.toConfig)

  private def numberType(valueString: String): model.BasicType = {
    try {
      valueString.toInt
      model.INTEGER
    }
    catch {
      case _: NumberFormatException =>
        try {
          valueString.toLong
          model.LONG
        }
        catch {
          case _: NumberFormatException =>
            try {
              valueString.toDouble
              model.DOUBLE
            }
            catch {
              case _: NumberFormatException => throw new AssertionError()
            }
        }
    }
  }
}

object ModelBuilder {

  import java.io.File

  import com.typesafe.config.ConfigFactory

  def apply(source: String, assumeAllRequired: Boolean = false): ModelBuildResult = {
    val config = ConfigFactory.parseString(source).resolve()
    new ModelBuilder(assumeAllRequired).build(config)
  }

  // $COVERAGE-OFF$
  def main(args: Array[String]): Unit = {
    val filename = args(0)
    val file = new File(filename)
    val bufSource = io.Source.fromFile(file)
    val source = bufSource.mkString.trim
    bufSource.close()
    //println("source:\n  |" + source.replaceAll("\n", "\n  |"))
    val result = ModelBuilder(source)
    println("ModelBuilderResult:")
    println(model.util.format(result.objectType))
  }

  // $COVERAGE-ON$
}
