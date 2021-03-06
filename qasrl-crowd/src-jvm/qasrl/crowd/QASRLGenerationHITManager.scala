package qasrl.crowd

import cats.implicits._

import spacro._
import spacro.tasks._

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

import akka.actor.ActorRef

import com.amazonaws.services.mturk.model.AssociateQualificationWithWorkerRequest

import com.typesafe.scalalogging.StrictLogging

import io.circe.{Encoder, Decoder}
import io.circe.syntax._

case class FlagBadSentence[SID](id: SID)

class QASRLGenerationHITManager[SID: Encoder : Decoder](
  helper: HITManager.Helper[QASRLGenerationPrompt[SID], List[VerbQA]],
  validationHelper: HITManager.Helper[QASRLValidationPrompt[SID], List[QASRLValidationAnswer]],
  validationActor: ActorRef,
  coverageDisqualificationTypeId: String,
  // sentenceTrackingActor: ActorRef,
  numAssignmentsForPrompt: QASRLGenerationPrompt[SID] => Int,
  initNumHITsToKeepActive: Int,
  _promptSource: Iterator[QASRLGenerationPrompt[SID]]
)(
  implicit annotationDataService: AnnotationDataService,
  settings: QASRLSettings
) extends NumAssignmentsHITManager[QASRLGenerationPrompt[SID], List[VerbQA]](
      helper,
      numAssignmentsForPrompt,
      initNumHITsToKeepActive,
      _promptSource
    )
    with StrictLogging {

  import helper._
  import config._
  import taskSpec.hitTypeId

  override def promptFinished(prompt: QASRLGenerationPrompt[SID]): Unit = {
    // sentenceTrackingActor ! GenerationFinished(prompt)
  }

  val badSentenceIdsFilename = "badSentenceIds"

  var badSentences = annotationDataService
    .loadLiveData(badSentenceIdsFilename)
    .map(_.mkString)
    .map(x => io.circe.parser.decode[Set[SID]](x).right.get)
    .toOption
    .foldK

  private[this] def flagBadSentence(id: SID) = {
    badSentences = badSentences + id
    save
    for {
      (prompt, hitInfos) <- activeHITInfosByPromptIterator
      if prompt.id == id
      HITInfo(hit, _) <- hitInfos
    } yield helper.expireHIT(hit)
  }

  val coverageStatsFilename = "coverageStats"

  var coverageStats: Map[String, List[Int]] = annotationDataService
    .loadLiveData(coverageStatsFilename)
    .map(_.mkString)
    .map(x => io.circe.parser.decode[Map[String, List[Int]]](x).right.get)
    .toOption
    .getOrElse(Map.empty[String, List[Int]])

  def christenWorker(workerId: String, numQuestions: Int) = {
    val newStats = numQuestions :: coverageStats.get(workerId).getOrElse(Nil)
    coverageStats = coverageStats.updated(workerId, newStats)
    val newQuestionsPerVerb = newStats.sum.toDouble / newStats.size
  }

  val feedbackFilename = "genFeedback"

  var feedbacks =
    annotationDataService
      .loadLiveData(feedbackFilename)
      .map(_.mkString)
      .map(x => io.circe.parser.decode[List[Assignment[List[VerbQA]]]](x).right.get)
      .toOption
      .foldK

  private[this] def save = {
    annotationDataService.saveLiveData(coverageStatsFilename, coverageStats.asJson.noSpaces)
    annotationDataService.saveLiveData(feedbackFilename, feedbacks.asJson.noSpaces)
    annotationDataService.saveLiveData(badSentenceIdsFilename, badSentences.asJson.noSpaces)
    logger.info("Generation data saved.")
  }

  override def reviewAssignment(
    hit: HIT[QASRLGenerationPrompt[SID]],
    assignment: Assignment[List[VerbQA]]
  ): Unit = {
    evaluateAssignment(hit, startReviewing(assignment), Approval(""))
    if (!assignment.feedback.isEmpty) {
      feedbacks = assignment :: feedbacks
      logger.info(s"Feedback: ${assignment.feedback}")
    }

    val newQuestionRecord = assignment.response.size :: coverageStats.get(assignment.workerId).foldK
    coverageStats = coverageStats.updated(assignment.workerId, newQuestionRecord)
    val verbsCompleted = newQuestionRecord.size
    val questionsPerVerb = newQuestionRecord.sum.toDouble / verbsCompleted
    if (questionsPerVerb < settings.generationCoverageQuestionsPerVerbThreshold &&
        verbsCompleted > settings.generationCoverageGracePeriod) {
      config.service.associateQualificationWithWorker(
        new AssociateQualificationWithWorkerRequest()
          .withQualificationTypeId(coverageDisqualificationTypeId)
          .withWorkerId(assignment.workerId)
          .withIntegerValue(1)
          .withSendNotification(true)
      )
    }
    val validationPrompt = QASRLValidationPrompt(
      hit.prompt,
      hit.hitTypeId,
      hit.hitId,
      assignment.assignmentId,
      assignment.response
    )
    validationActor ! validationHelper.Message.AddPrompt(validationPrompt)
    // sentenceTrackingActor ! ValidationBegun(validationPrompt)
  }

  override lazy val receiveAux2: PartialFunction[Any, Unit] = {
    case SaveData => save
    case Pring    => println("Generation manager pringed.")
    case fbs: FlagBadSentence[SID] =>
      fbs match {
        case FlagBadSentence(id) => flagBadSentence(id)
      }
    case ChristenWorker(workerId, numQuestions) =>
      christenWorker(workerId, numQuestions)
  }
}
