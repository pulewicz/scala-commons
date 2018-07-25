package com.avsystem.commons
package rest

import com.avsystem.commons.rpc._

@methodTag[RestMethodTag]
case class RestMetadata[T](
  @multi @tagged[Prefix](whenUntagged = new Prefix)
  @paramTag[RestParamTag](defaultTag = new Path)
  prefixMethods: Map[String, PrefixMetadata[_]],

  @multi @tagged[GET]
  @paramTag[RestParamTag](defaultTag = new Query)
  httpGetMethods: Map[String, HttpMethodMetadata[_]],

  @multi @tagged[BodyMethodTag](whenUntagged = new POST)
  @paramTag[RestParamTag](defaultTag = new JsonBodyParam)
  httpBodyMethods: Map[String, HttpMethodMetadata[_]]
) {
  val httpMethods: Map[String, HttpMethodMetadata[_]] =
    httpGetMethods ++ httpBodyMethods

  def ensureUniqueParams(prefixes: List[(String, PrefixMetadata[_])]): Unit = {
    def ensureUniqueParams(methodName: String, method: RestMethodMetadata[_]): Unit = {
      for {
        (prefixName, prefix) <- prefixes
        headerParam <- method.parametersMetadata.headers.keys
        if prefix.parametersMetadata.headers.contains(headerParam)
      } throw new InvalidRestApiException(
        s"Header parameter $headerParam of $methodName collides with header parameter of the same name in prefix $prefixName")

      for {
        (prefixName, prefix) <- prefixes
        queryParam <- method.parametersMetadata.query.keys
        if prefix.parametersMetadata.query.contains(queryParam)
      } throw new InvalidRestApiException(
        s"Query parameter $queryParam of $methodName collides with query parameter of the same name in prefix $prefixName")
    }

    prefixMethods.foreach {
      case (name, prefix) =>
        ensureUniqueParams(name, prefix)
        prefix.result.value.ensureUniqueParams((name, prefix) :: prefixes)
    }
    (httpGetMethods ++ httpBodyMethods).foreach {
      case (name, method) => ensureUniqueParams(name, method)
    }
  }

  def ensureUnambiguousPaths(): Unit = {
    val trie = new RestMetadata.Trie
    trie.fillWith(this)
    trie.mergeWildcardToNamed()
    val ambiguities = new MListBuffer[(String, List[String])]
    trie.collectAmbiguousCalls(ambiguities)
    if (ambiguities.nonEmpty) {
      val problems = ambiguities.map { case (path, chains) =>
        s"$path may result from multiple calls:\n  ${chains.mkString("\n  ")}"
      }
      throw new InvalidRestApiException(s"REST API has ambiguous paths:\n${problems.mkString("\n")}")
    }
  }

  def resolvePath(method: HttpMethod, path: List[PathValue]): Opt[ResolvedPath] = {
    def resolve(method: HttpMethod, path: List[PathValue]): Iterator[ResolvedPath] = {
      val asFinalCall = for {
        (rpcName, m) <- httpMethods.iterator if m.method == method
        (pathParams, Nil) <- m.extractPathParams(path)
      } yield ResolvedPath(Nil, RestMethodCall(rpcName, pathParams, m), m.singleBody)

      val usingPrefix = for {
        (rpcName, prefix) <- prefixMethods.iterator
        (pathParams, pathTail) <- prefix.extractPathParams(path).iterator
        suffixPath <- prefix.result.value.resolvePath(method, pathTail)
      } yield suffixPath.prepend(rpcName, pathParams, prefix)

      asFinalCall ++ usingPrefix
    }
    resolve(method, path).toList match {
      case Nil => Opt.Empty
      case single :: Nil => Opt(single)
      case multiple =>
        val pathStr = path.iterator.map(_.value).mkString("/")
        val callsRepr = multiple.iterator.map(p => s"  ${p.rpcChainRepr}").mkString("\n", "\n", "")
        throw new RestException(s"path $pathStr is ambiguous, it could map to following calls:$callsRepr")
    }
  }
}
object RestMetadata extends RpcMetadataCompanion[RestMetadata] {
  private class Trie {
    val rpcChains: Map[HttpMethod, MBuffer[String]] =
      HttpMethod.values.mkMap(identity, _ => new MArrayBuffer[String])

    val byName: MMap[String, Trie] = new MHashMap
    var wildcard: Opt[Trie] = Opt.Empty

    def forPattern(pattern: List[PathPatternElement]): Trie = pattern match {
      case Nil => this
      case PathName(PathValue(pathName)) :: tail =>
        byName.getOrElseUpdate(pathName, new Trie).forPattern(tail)
      case PathParam(_) :: tail =>
        wildcard.getOrElse(new Trie().setup(t => wildcard = Opt(t))).forPattern(tail)
    }

    def fillWith(metadata: RestMetadata[_], prefixStack: List[(String, PrefixMetadata[_])] = Nil): Unit = {
      def prefixChain: String =
        prefixStack.reverseIterator.map({ case (k, _) => k }).mkStringOrEmpty("", "->", "->")

      metadata.prefixMethods.foreach { case entry@(rpcName, pm) =>
        if (prefixStack.contains(entry)) {
          throw new InvalidRestApiException(
            s"call chain $prefixChain$rpcName is recursive, recursively defined server APIs are forbidden")
        }
        forPattern(pm.pathPattern).fillWith(pm.result.value, entry :: prefixStack)
      }
      metadata.httpMethods.foreach { case (rpcName, hm) =>
        forPattern(hm.pathPattern).rpcChains(hm.method) += s"$prefixChain${rpcName.stripPrefix(s"${hm.method}_")}"
      }
    }

    private def merge(other: Trie): Unit = {
      HttpMethod.values.foreach { meth =>
        rpcChains(meth) ++= other.rpcChains(meth)
      }
      for (w <- wildcard; ow <- other.wildcard) w.merge(ow)
      wildcard = wildcard orElse other.wildcard
      other.byName.foreach { case (name, trie) =>
        byName.getOrElseUpdate(name, new Trie).merge(trie)
      }
    }

    def mergeWildcardToNamed(): Unit = wildcard.foreach { wc =>
      wc.mergeWildcardToNamed()
      byName.values.foreach { trie =>
        trie.merge(wc)
        trie.mergeWildcardToNamed()
      }
    }

    def collectAmbiguousCalls(ambiguities: MBuffer[(String, List[String])], pathPrefix: List[String] = Nil): Unit = {
      rpcChains.foreach { case (method, chains) =>
        if (chains.size > 1) {
          val path = pathPrefix.reverse.mkString(s"$method /", "/", "")
          ambiguities += ((path, chains.toList))
        }
      }
      wildcard.foreach(_.collectAmbiguousCalls(ambiguities, "*" :: pathPrefix))
      byName.foreach { case (name, trie) =>
        trie.collectAmbiguousCalls(ambiguities, name :: pathPrefix)
      }
    }
  }
}

sealed trait PathPatternElement
case class PathName(value: PathValue) extends PathPatternElement
case class PathParam(parameter: PathParamMetadata[_]) extends PathPatternElement

sealed abstract class RestMethodMetadata[T] extends TypedMetadata[T] {
  def methodPath: List[PathValue]
  def parametersMetadata: RestParametersMetadata

  val pathPattern: List[PathPatternElement] =
    methodPath.map(PathName) ++ parametersMetadata.path.flatMap(pp => PathParam(pp) :: pp.pathSuffix.map(PathName))

  def applyPathParams(params: List[PathValue]): List[PathValue] = {
    def loop(params: List[PathValue], pattern: List[PathPatternElement]): List[PathValue] =
      (params, pattern) match {
        case (Nil, Nil) => Nil
        case (_, PathName(patternHead) :: patternTail) => patternHead :: loop(params, patternTail)
        case (param :: paramsTail, PathParam(_) :: patternTail) => param :: loop(paramsTail, patternTail)
        case _ => throw new IllegalArgumentException(
          s"got ${params.size} path params, expected ${parametersMetadata.path.size}")
      }
    loop(params, pathPattern)
  }

  def extractPathParams(path: List[PathValue]): Opt[(List[PathValue], List[PathValue])] = {
    def loop(path: List[PathValue], pattern: List[PathPatternElement]): Opt[(List[PathValue], List[PathValue])] =
      (path, pattern) match {
        case (pathTail, Nil) => Opt((Nil, pathTail))
        case (param :: pathTail, PathParam(_) :: patternTail) =>
          loop(pathTail, patternTail).map { case (params, tail) => (param :: params, tail) }
        case (pathHead :: pathTail, PathName(patternHead) :: patternTail) if pathHead == patternHead =>
          loop(pathTail, patternTail)
        case _ => Opt.Empty
      }
    loop(path, pathPattern)
  }
}

case class PrefixMetadata[T](
  @reifyAnnot methodTag: Prefix,
  @composite parametersMetadata: RestParametersMetadata,
  @checked @infer result: RestMetadata.Lazy[T]
) extends RestMethodMetadata[T] {
  def methodPath: List[PathValue] = PathValue.split(methodTag.path)
}

case class HttpMethodMetadata[T](
  @reifyAnnot methodTag: HttpMethodTag,
  @composite parametersMetadata: RestParametersMetadata,
  @multi @tagged[BodyTag] bodyParams: Map[String, BodyParamMetadata[_]],
  @checked @infer responseType: HttpResponseType[T]
) extends RestMethodMetadata[T] {
  val method: HttpMethod = methodTag.method
  val singleBody: Boolean = bodyParams.values.exists(_.singleBody)
  def methodPath: List[PathValue] = PathValue.split(methodTag.path)
}

/**
  * Currently just a marker typeclass used by [[RestMetadata]] materialization to distinguish between
  * prefix methods and HTTP methods. In the future this typeclass may contain some additional information, e.g.
  * type metadata for generating swagger definitions.
  */
trait HttpResponseType[T]
object HttpResponseType {
  implicit def forFuture[T]: HttpResponseType[Future[T]] =
    new HttpResponseType[Future[T]] {}
}

case class RestParametersMetadata(
  @multi @tagged[Path] path: List[PathParamMetadata[_]],
  @multi @tagged[Header] headers: Map[String, HeaderParamMetadata[_]],
  @multi @tagged[Query] query: Map[String, QueryParamMetadata[_]]
)

case class PathParamMetadata[T](
  @reifyName(rpcName = true) rpcName: String,
  @reifyAnnot pathAnnot: Path
) extends TypedMetadata[T] {
  val pathSuffix: List[PathValue] = PathValue.split(pathAnnot.pathSuffix)
}

case class HeaderParamMetadata[T]() extends TypedMetadata[T]
case class QueryParamMetadata[T]() extends TypedMetadata[T]
case class BodyParamMetadata[T](@isAnnotated[Body] singleBody: Boolean) extends TypedMetadata[T]

class InvalidRestApiException(msg: String) extends RestException(msg)