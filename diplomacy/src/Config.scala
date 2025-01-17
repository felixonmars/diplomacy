// Copyright 2016-2019 SiFive, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package diplomacy

object config {
  abstract class Field[T] private (val default: Option[T]) {
    def this() = this(None)
    def this(default: T) = this(Some(default))
  }

  abstract class View {
    final def apply[T](pname: Field[T]): T = apply(pname, this)
    final def apply[T](pname: Field[T], site: View): T = {
      val out = find(pname, site)
      require(out.isDefined, s"Key ${pname} is not defined in Parameters")
      out.get
    }

    final def lift[T](pname: Field[T]): Option[T] = lift(pname, this)
    final def lift[T](pname: Field[T], site: View): Option[T] = find(pname, site).map(_.asInstanceOf[T])

    protected[config] def find[T](pname: Field[T], site: View): Option[T]
  }

  abstract class Parameters extends View {
    // x alter y: settings in 'y' overrule settings in 'x'
    final def alter(rhs: Parameters): Parameters =
      new ChainParameters(rhs, this)

    final def alter(f: (View, View, View) => PartialFunction[Any, Any]): Parameters =
      alter(Parameters(f))

    final def alterPartial(f: PartialFunction[Any, Any]): Parameters =
      alter(Parameters((_, _, _) => f))

    final def alterMap(m: Map[Any, Any]): Parameters =
      alter(new MapParameters(m))

    protected[config] def chain[T](site: View, tail:     View, pname: Field[T]): Option[T]
    protected[config] def find[T](pname: Field[T], site: View) = chain(site, new TerminalView, pname)

    // x orElse y: settings in 'x' overrule settings in 'y'
    final def orElse(x: Parameters): Parameters = x.alter(this)

    // Please use 'alter' or 'orElse' instead of '++'.
    // People expect this to be alter (like Map ++ Map), but it's orElse.
    final def ++(x: Parameters): Parameters = orElse(x)
  }

  object Parameters {
    def empty: Parameters = new EmptyParameters
    def apply(f: (View, View, View) => PartialFunction[Any, Any]): Parameters = new PartialParameters(f)
  }

  class Config(p: Parameters) extends Parameters {
    def this(f: (View, View, View) => PartialFunction[Any, Any]) = this(Parameters(f))

    protected[config] def chain[T](site: View, tail: View, pname: Field[T]) = p.chain(site, tail, pname)
    override def toString = this.getClass.getSimpleName
    def toInstance = this
  }

  // Internal implementation:

  private class TerminalView extends View {
    def find[T](pname: Field[T], site: View): Option[T] = pname.default
  }

  private class ChainView(head: Parameters, tail: View) extends View {
    def find[T](pname: Field[T], site: View) = head.chain(site, tail, pname)
  }

  private class ChainParameters(x: Parameters, y: Parameters) extends Parameters {
    def chain[T](site: View, tail: View, pname: Field[T]) = x.chain(site, new ChainView(y, tail), pname)
  }

  private class EmptyParameters extends Parameters {
    def chain[T](site: View, tail: View, pname: Field[T]) = tail.find(pname, site)
  }

  private class PartialParameters(f: (View, View, View) => PartialFunction[Any, Any]) extends Parameters {
    protected[config] def chain[T](site: View, tail: View, pname: Field[T]) = {
      val g = f(site, this, tail)
      if (g.isDefinedAt(pname)) Some(g.apply(pname).asInstanceOf[T]) else tail.find(pname, site)
    }
  }

  private class MapParameters(map: Map[Any, Any]) extends Parameters {
    protected[config] def chain[T](site: View, tail: View, pname: Field[T]) = {
      val g = map.get(pname)
      if (g.isDefined) Some(g.get.asInstanceOf[T]) else tail.find(pname, site)
    }
  }
}
