import React from 'react'
import Accordion from 'react-bootstrap/Accordion'
import ListGroup from 'react-bootstrap/ListGroup'
import Button from 'react-bootstrap/Button'
import rtlLanguages from '../../constants/rtlLanguages'
import { scriptIsDifferent } from '../../shared/utilityFunctions'

const IpfDetail = ({ ipf, destLanguage, dfltLanguage }) => {
  const renderRelations = () => {
    return (
      <div>
        <Accordion>
          <Accordion.Toggle as={Button} variant="outline-primary" eventKey="0">
            Show Detail
          </Accordion.Toggle>
          <Accordion.Collapse eventKey="0">
            <ListGroup>
              {ipf.relations.map((relation) => (
                <ListGroup.Item key={relation.relNode}>
                  <strong>Node ID: {relation.relNode}</strong>
                  <ListGroup>
                    <ListGroup.Item>
                      <strong>Added: </strong>
                      {relation.added}
                    </ListGroup.Item>
                    <ListGroup.Item>
                      <strong>Removed: </strong>
                      {relation.removed}
                    </ListGroup.Item>
                  </ListGroup>
                </ListGroup.Item>
              ))}
            </ListGroup>
          </Accordion.Collapse>
        </Accordion>
      </div>
    )
  }

  const differentScript = scriptIsDifferent(destLanguage, dfltLanguage)

  if (
    (differentScript &&
      ipf.relations.some(
        (relation) =>
          relation.added.length !== 0 || relation.removed.length !== 0
      )) ||
    !differentScript
  ) {
    return renderRelations()
  }

  return null
}

export default IpfDetail
