package dotty.tools.scaladoc

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Success,Failure}

import org.scalajs.dom._
import org.scalajs.dom.ext._
import scala.scalajs.js.annotation.JSExportTopLevel
import org.scalajs.dom.ext.Ajax
import scala.scalajs.js
import scala.scalajs.js.JSON

trait Versions extends js.Object:
  def versions: js.Dictionary[String]

class DropdownHandler:

  val KEY = "versions-json"
  val UNDEFINED_VERSIONS = "undefined_versions"

  private def addVersionsList(json: String) =
    val ver = JSON.parse(json).asInstanceOf[Versions]
    val ddc = document.getElementById("dropdown-content")
    for (k, v) <- ver.versions do
      var child = document.createElement("a").asInstanceOf[html.Anchor]
      child.href = v
      child.text = k
      ddc.appendChild(child)
    val arrow = document.createElement("span").asInstanceOf[html.Span]
    arrow.classList.add("ar")
    document.getElementById("dropdown-button").appendChild(arrow)

  private def disableButton() =
    val btn = document.getElementById("dropdown-button").asInstanceOf[html.Button]
    btn.disabled = true
    btn.classList.remove("dropdownbtnactive")

  private def getURLContent(url: String): Future[String] = Ajax.get(url).map(_.responseText)

  window.sessionStorage.getItem(KEY) match
    case null => // If no key, returns null
      js.typeOf(Globals.versionsDictionaryUrl) match
        case "undefined" =>
          window.sessionStorage.setItem(KEY, UNDEFINED_VERSIONS)
          disableButton()
        case _ =>
          getURLContent(Globals.versionsDictionaryUrl).onComplete {
            case Success(json: String) =>
              window.sessionStorage.setItem(KEY, json)
              addVersionsList(json)
            case Failure(_) =>
              window.sessionStorage.setItem(KEY, UNDEFINED_VERSIONS)
              disableButton()
          }
    case value => value match
      case UNDEFINED_VERSIONS =>
        disableButton()
      case json =>
        addVersionsList(json)

  document.addEventListener("click", (e: Event) => {
    if e.target.asInstanceOf[html.Element].id != "dropdown-button" then
      document.getElementById("dropdown-content").classList.remove("show")
      document.getElementById("dropdown-button").classList.remove("expanded")
  })

  document.getElementById("version").asInstanceOf[html.Span].onclick = (e: Event) => {
    e.stopPropagation
  }
end DropdownHandler

@JSExportTopLevel("dropdownHandler")
def dropdownHandler() =
  if document.getElementById("dropdown-content").getElementsByTagName("a").size > 0 &&
     window.getSelection.toString.length == 0 then
    document.getElementById("dropdown-content").classList.toggle("show")
    document.getElementById("dropdown-button").classList.toggle("expanded")

@JSExportTopLevel("filterFunction")
def filterFunction() =
  val input = document.getElementById("dropdown-input").asInstanceOf[html.Input]
  val filter = input.value.toUpperCase
  val div = document.getElementById("dropdown-content")
  val as = div.getElementsByTagName("a")

  as.foreach { a =>
    val txtValue = a.innerText
    val cl = a.asInstanceOf[html.Anchor].classList
    if txtValue.toUpperCase.indexOf(filter) > -1 then
      cl.remove("filtered")
    else
      cl.add("filtered")
  }
