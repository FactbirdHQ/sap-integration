import com.sap.gateway.ip.core.customdev.util.Message;
import groovy.util.XmlSlurper;
import groovy.xml.MarkupBuilder;
import groovy.util.slurpersupport.NodeChildren;
import groovy.json.JsonBuilder;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

def String formatTimePair(String dateTime, String relativeTime) {
    def dateTimeInstant = Instant.parse(dateTime + "Z")
    def relativeTimeInstant = Instant.parse(relativeTime + "Z")

    def absoluteTime = dateTimeInstant.plusMillis(relativeTimeInstant.toEpochMilli())

    DateTimeFormatter.ISO_INSTANT.format(absoluteTime)
}

def zipTimestampsIntoMillis(NodeChildren startDates, NodeChildren startTimes) {
    [startDates.iterator().collect(), startTimes.iterator().collect()]
        .transpose()
        .findAll { it -> it[0].text() && it[1].text() }.collect { it ->
            def dateTimeInstant = Instant.parse(it[0].text() + "Z")
            def relativeTimeInstant = Instant.parse(it[1].text() + "Z")

            dateTimeInstant.plusMillis(relativeTimeInstant.toEpochMilli())
        }
}

// If you want this dynamically, you can match names against ids listed from
// https://api.cloud.factbird.com/v1/docs/#query-lines or a looped process call
// against https://api.cloud.factbird.com/v1/docs/#query-linesPaginated (we have
// not yet stabilized this latter one as of this writing)
def lineIdMapping = [
    '1710': 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
]

def Message processData(Message message) {
    def productionOrder = new XmlSlurper().parseText(message.getProperty("productionOrder"))
    def productionOrderSerialNumber = new XmlSlurper().parseText(message.getProperty("productionOrderSerialNumber"))

    def messageLog = messageLogFactory.getMessageLog(message);
    if (messageLog != null) {
        messageLog.addAttachmentAsString("productionOrder", message.getProperty("productionOrder"), "application/json");
        messageLog.addAttachmentAsString("productionOrderSerialNumber", message.getProperty("productionOrderSerialNumber"), "application/json");
    }

    def builder = new JsonBuilder()

    def output = []

    productionOrder.A_ProductionOrder_2Type.each { order ->
        def serialNumber = productionOrderSerialNumber.children().find { type ->
            type.ManufacturingOrder.text() == order.ManufacturingOrder.text()
        }

        if (!serialNumber) {
            return
        }

        def released = order.to_ProductionOrderStatus.children().any { status ->
            status.StatusShortName.text() == "SETC"
            //status.StatusShortName.text() == "REL"
        }

        if (!released) {
            return
        }

        def operation = order.to_ProductionOrderOperation.A_ProductionOrderOperation_2Type
        
        // Production Orders may propagate from one work center to another - we
        // simply make the assumption it'll remain at the same location.
        def initiallyAllocatedWorkCenter = order.WorkCenter[0]

        // With that same assumption, all operations are expected to take place
        // in order, so for actualStart and actualStop we find the extrema
        def actualStart = zipTimestampsIntoMillis(operation.OpActualExecutionStartDate, operation.OpActualExecutionStartTime).min()
        if (actualStart) {
            DateTimeFormatter.ISO_INSTANT.format(actualStart)
        }
        def actualStop = zipTimestampsIntoMillis(operation.OpActualExecutionEndDate, operation.OpActualExecutionEndTime).max()
        if (actualStop) {
            DateTimeFormatter.ISO_INSTANT.format(actualStop)
        }

        output << [
            externalLineId: order.Plant.text() + initiallyAllocatedWorkCenter.text(),
            batchNumber: order.ManufacturingOrder.text(),
            itemNumber: serialNumber.Product.text(),
            amount: order.TotalQuantity.text(),
            plannedStart: formatTimePair(order.MfgOrderPlannedStartDate.text(), order.MfgOrderPlannedStartTime.text()),
            actualStart: actualStart,
            actualStop: actualStop
        ]
    }

    def resultXml = new StringWriter()
    def xmlBuilder = new MarkupBuilder(resultXml)

    xmlBuilder.'production-orders' {
        output.each { row ->
            'order'(action: row.actualStop ? 'stop' : (row.actualStart ? 'start' : 'create')) {
                'line-id'(lineIdMapping[row.externalLineId])
                'batch-number'(row.batchNumber)
                'item-number'(row.itemNumber)
                'amount'(row.amount)
                'planned-start'(row.plannedStart)
                'actual-start'(row.actualStart)
                'actual-stop'(row.actualStop)
            }
        }
    }

    message.setBody(resultXml.toString())
    
    return message;
}
