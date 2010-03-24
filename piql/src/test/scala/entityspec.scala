package edu.berkeley.cs.scads.test

import org.specs._
import org.specs.runner.JUnit4

import edu.berkeley.cs.scads.piql.{Compiler, Entity}
import edu.berkeley.cs.scads.storage.{TestScalaEngine}

class DynamicDispatchObject(obj: AnyRef) {
  val cls = obj.getClass

  def setField(fieldName: String, value: Object): Unit = {
    val mutator = cls.getMethods.filter(_.getName.equals(fieldName + "_$eq")).first
    mutator.invoke(obj, value)
  }

  def getField(fieldName: String): Any = {
    val accesor = cls.getMethod(fieldName)
    accesor.invoke(obj)
  }
}

class DynamicDispatchClass[ClassType](cls: Class[ClassType]) {
  def newInstance2(args: AnyRef*): ClassType = {
    val constructor = cls.getConstructor(args.map(_.getClass):_*)
    constructor.newInstance(args:_*).asInstanceOf[ClassType]
  }
}

object EntitySpec extends SpecificationWithJUnit("Scads Entity Specification") {
  implicit def toDynDispObj(obj: AnyRef): DynamicDispatchObject = new DynamicDispatchObject(obj)
  implicit def toDynDispClass[T](cls: Class[T]): DynamicDispatchClass[T] = new DynamicDispatchClass(cls)

  lazy val e1 = Compiler.getClassLoader("""
      ENTITY e1 {
        string sf1,
        int if1
        PRIMARY(sf1)
      }
  """).loadClass("e1").asInstanceOf[Class[Entity]]

  "PIQL Entities" should {
    "be instantiable" in {
      val a = e1.newInstance2(TestScalaEngine.cluster)
      true must_== true
    }

   "serialize field values" in {
      val a = e1.newInstance2(TestScalaEngine.cluster)
      a.setField("sf1", "test")
      a.setField("if1", new Integer(1))

      val b = e1.newInstance2(TestScalaEngine.cluster)
      b.key.parse(a.key.toBytes)
      b.value.parse(a.value.toBytes)

      b.getField("sf1") must_== "test"
      b.getField("if1") must_== 1
    }

    "retain field values through load/store" in {
      val a = e1.newInstance2(TestScalaEngine.cluster)
      TestScalaEngine.createNamespace("ent_e1", a.key.getSchema, a.value.getSchema)
      a.setField("sf1", "test")
      a.setField("if1", new Integer(1))
      a.save

      val b = e1.newInstance2(TestScalaEngine.cluster)
      b.load(a.key)

      b.getField("sf1") must_== "test"
      b.getField("if1") must_== 1
    }
  }
}

class EntityTest extends JUnit4(EntitySpec)
